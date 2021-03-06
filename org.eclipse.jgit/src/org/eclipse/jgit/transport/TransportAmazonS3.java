/*
 * Copyright (C) 2008, Shawn O. Pearce <spearce@spearce.org>
 * and other copyright owners as documented in the project's IP log.
 *
 * This program and the accompanying materials are made available
 * under the terms of the Eclipse Distribution License v1.0 which
 * accompanies this distribution, is reproduced below, and is
 * available at http://www.eclipse.org/org/documents/edl-v10.php
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or
 * without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above
 *   copyright notice, this list of conditions and the following
 *   disclaimer in the documentation and/or other materials provided
 *   with the distribution.
 *
 * - Neither the name of the Eclipse Foundation, Inc. nor the
 *   names of its contributors may be used to endorse or promote
 *   products derived from this software without specific prior
 *   written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 * CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package org.eclipse.jgit.transport;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.text.MessageFormat;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

import org.eclipse.jgit.errors.NotSupportedException;
import org.eclipse.jgit.errors.TransportException;
import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.internal.storage.file.PackLock;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdRef;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Ref.Storage;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.SymbolicRef;
import org.eclipse.jgit.util.IO;

/**
 * Transport over the non-Git aware Amazon S3 protocol.
 * <p>
 * This transport communicates with the Amazon S3 servers (a non-free commercial
 * hosting service that users must subscribe to). Some users may find transport
 * to and from S3 to be a useful backup service.
 * <p>
 * The transport does not require any specialized Git support on the remote
 * (server side) repository, as Amazon does not provide any such support.
 * Repository files are retrieved directly through the S3 API, which uses
 * extended HTTP/1.1 semantics. This make it possible to read or write Git data
 * from a remote repository that is stored on S3.
 * <p>
 * Unlike the HTTP variant (see {@link TransportHttp}) we rely upon being able
 * to list objects in a bucket, as the S3 API supports this function. By listing
 * the bucket contents we can avoid relying on <code>objects/info/packs</code>
 * or <code>info/refs</code> in the remote repository.
 * <p>
 * Concurrent pushing over this transport is not supported. Multiple concurrent
 * push operations may cause confusion in the repository state.
 *
 * @see WalkFetchConnection
 * @see WalkPushConnection
 */
public class TransportAmazonS3 extends HttpTransport implements WalkTransport {
	static final String S3_SCHEME = "amazon-s3"; //$NON-NLS-1$

	static final TransportProtocol PROTO_S3 = new TransportProtocol() {
		public String getName() {
			return "Amazon S3"; //$NON-NLS-1$
		}

		public Set<String> getSchemes() {
			return Collections.singleton(S3_SCHEME);
		}

		public Set<URIishField> getRequiredFields() {
			return Collections.unmodifiableSet(EnumSet.of(URIishField.USER,
					URIishField.HOST, URIishField.PATH));
		}

		public Set<URIishField> getOptionalFields() {
			return Collections.unmodifiableSet(EnumSet.of(URIishField.PASS));
		}

		public Transport open(URIish uri, Repository local, String remoteName)
				throws NotSupportedException {
			return new TransportAmazonS3(local, uri);
		}

		public Transport open(URIish uri) throws NotSupportedException {
			return new TransportAmazonS3(uri);
		}
	};

	/** User information necessary to connect to S3. */
	private final AmazonS3 s3;

	/** Bucket the remote repository is stored in. */
	private final String bucket;

	/**
	 * Key prefix which all objects related to the repository start with.
	 * <p>
	 * The prefix does not start with "/".
	 * <p>
	 * The prefix does not end with "/". The trailing slash is stripped during
	 * the constructor if a trailing slash was supplied in the URIish.
	 * <p>
	 * All files within the remote repository start with
	 * <code>keyPrefix + "/"</code>.
	 */
	private final String keyPrefix;

	TransportAmazonS3(final Repository local, final URIish uri)
			throws NotSupportedException {
		super(local, uri);
		s3 = new AmazonS3(loadProperties());
		bucket = uri.getHost();

		String p = uri.getPath();
		if (p.startsWith("/")) //$NON-NLS-1$
			p = p.substring(1);
		if (p.endsWith("/")) //$NON-NLS-1$
			p = p.substring(0, p.length() - 1);
		keyPrefix = p;
	}

	TransportAmazonS3(final URIish uri) throws NotSupportedException {
		super(uri);
		s3 = new AmazonS3(loadProperties());
		bucket = uri.getHost();

		String p = uri.getPath();
		if (p.startsWith("/")) //$NON-NLS-1$
			p = p.substring(1);
		if (p.endsWith("/")) //$NON-NLS-1$
			p = p.substring(0, p.length() - 1);
		keyPrefix = p;
	}

	private Properties loadProperties() throws NotSupportedException {
		if ("IAM".equals(uri.getUser())) { //$NON-NLS-1$
			try {
				return instanceCredentialProfile();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
		if ("ENV".equals(uri.getUser())) { //$NON-NLS-1$
			return environmentVariableProfile();
		}
		File propsFile;
		if (local != null) {
			if (local.getDirectory() != null) {
				propsFile = new File(local.getDirectory(), uri.getUser());
				if (propsFile.isFile())
					return loadPropertiesFile(propsFile);
			}

			if (local.getFS() != null) {
				propsFile = new File(local.getFS().userHome(), uri.getUser());
				if (propsFile.isFile())
					return loadPropertiesFile(propsFile);
			}
		}
		propsFile = new File(uri.getUser());
		if (propsFile.isFile())
			return loadPropertiesFile(propsFile);

		Properties props = new Properties();
		String user = uri.getUser();
		String pass = uri.getPass();
		if (user != null && pass != null) {
		        props.setProperty("accesskey", user); //$NON-NLS-1$
		        props.setProperty("secretkey", pass); //$NON-NLS-1$
		} else
			throw new NotSupportedException(MessageFormat.format(
					JGitText.get().cannotReadFile, propsFile));
		return props;
	}

	// see
	// https://github.com/aws/aws-sdk-java/blob/master/aws-java-sdk-core/src/main/java/com/amazonaws/auth/EnvironmentVariableCredentialsProvider.java
	private Properties environmentVariableProfile() {
		Properties props = new Properties();
		String accesskey = System.getenv("AWS_ACCESS_KEY_ID"); //$NON-NLS-1$
		if(accesskey==null){
			accesskey = System.getenv("AWS_ACCESS_KEY"); //$NON-NLS-1$
		}
		String secretkey = System.getenv("AWS_SECRET_KEY"); //$NON-NLS-1$
		if (secretkey == null) {
			secretkey = System.getenv("AWS_SECRET_ACCESS_KEY"); //$NON-NLS-1$
		}
		if (accesskey != null) {
			props.setProperty("accesskey", accesskey); //$NON-NLS-1$
		}
		if (secretkey != null) {
			props.setProperty("secretkey", secretkey); //$NON-NLS-1$
		}
		String token = System.getenv("AWS_SESSION_TOKEN"); //$NON-NLS-1$
		if (token != null) {
			props.setProperty("token", token); //$NON-NLS-1$
		}
		return props;
	}

	private static final Pattern jsonStringPropertyPattern = Pattern
			.compile("\"([^\"]*)\"\\s*:\\s*\"([^\"]*)\""); //$NON-NLS-1$

	private static final String roleUrl = "http://169.254.169.254/latest/meta-data/iam/security-credentials/"; //$NON-NLS-1$

	private static Properties instanceCredentialProfile() throws IOException {
		Properties props = new Properties();
		String role = getHttpBody(roleUrl);
		String jsonBody = getHttpBody(roleUrl + role);
		Matcher matcher = jsonStringPropertyPattern.matcher(jsonBody);
		HashMap<String, String> values = new HashMap<String, String>();
		while (matcher.find()) {
			values.put(matcher.group(1), matcher.group(2));
		}
		props.setProperty("accesskey", values.get("AccessKeyId")); //$NON-NLS-1$ //$NON-NLS-2$
		props.setProperty("secretkey", values.get("SecretAccessKey")); //$NON-NLS-1$ //$NON-NLS-2$
		props.setProperty("token", values.get("Token")); //$NON-NLS-1$ //$NON-NLS-2$
		return props;
	}

	private static String getHttpBody(String url) throws IOException {
		final URL u = new URL(url);
		final HttpURLConnection c = (HttpURLConnection) u.openConnection();
		final InputStream in = c.getInputStream();
		final int len = c.getContentLength();
		try {
			return new String(IO.readWholeStream(in, len == -1 ? 1000 : len)
					.array(), "UTF-8"); //$NON-NLS-1$
		} finally {
			in.close();
		}
	}

	private static Properties loadPropertiesFile(File propsFile)
			throws NotSupportedException {
		try {
			return AmazonS3.properties(propsFile);
		} catch (IOException e) {
			throw new NotSupportedException(MessageFormat.format(
					JGitText.get().cannotReadFile, propsFile), e);
		}
	}

	@Override
	public FetchConnection openFetch() throws TransportException {
		final DatabaseS3 c = new DatabaseS3(bucket, keyPrefix + "/objects"); //$NON-NLS-1$
		if (local != null) {
			final WalkFetchConnection r = new WalkFetchConnection(this, c);
			r.available(c.readAdvertisedRefs());
			return r;
		} else {
			final Map<String, Ref> refsMap = c.readAdvertisedRefs();
			return new FetchConnection() {

				@Override
				public Map<String, Ref> getRefsMap() {
					return refsMap;
				}

				@Override
				public Collection<Ref> getRefs() {
					return getRefsMap().values();
				}

				@Override
				public Ref getRef(String name) {
					return refsMap.get(name);
				}

				@Override
				public void close() {
					c.close();
				}

				@Override
				public String getMessages() {
					throw new UnsupportedOperationException();
				}

				@Override
				public void fetch(ProgressMonitor monitor,
						Collection<Ref> want, Set<ObjectId> have)
						throws TransportException {
					throw new UnsupportedOperationException();
				}

				@Override
				public void fetch(ProgressMonitor monitor,
						Collection<Ref> want, Set<ObjectId> have,
						OutputStream out) throws TransportException {
					throw new UnsupportedOperationException();
				}

				@Override
				public boolean didFetchIncludeTags() {
					throw new UnsupportedOperationException();
				}

				@Override
				public boolean didFetchTestConnectivity() {
					throw new UnsupportedOperationException();
				}

				@Override
				public void setPackLockMessage(String message) {
					throw new UnsupportedOperationException();
				}

				@Override
				public Collection<PackLock> getPackLocks() {
					throw new UnsupportedOperationException();
				}
			};
		}
	}

	@Override
	public PushConnection openPush() throws TransportException {
		final DatabaseS3 c = new DatabaseS3(bucket, keyPrefix + "/objects"); //$NON-NLS-1$
		final WalkPushConnection r = new WalkPushConnection(this, c);
		r.available(c.readAdvertisedRefs());
		return r;
	}

	@Override
	public void close() {
		// No explicit connections are maintained.
	}

	class DatabaseS3 extends WalkRemoteObjectDatabase {
		private final String bucketName;

		private final String objectsKey;

		DatabaseS3(final String b, final String o) {
			bucketName = b;
			objectsKey = o;
		}

		private String resolveKey(String subpath) {
			if (subpath.endsWith("/")) //$NON-NLS-1$
				subpath = subpath.substring(0, subpath.length() - 1);
			String k = objectsKey;
			while (subpath.startsWith(ROOT_DIR)) {
				k = k.substring(0, k.lastIndexOf('/'));
				subpath = subpath.substring(3);
			}
			return k + "/" + subpath; //$NON-NLS-1$
		}

		@Override
		URIish getURI() {
			URIish u = new URIish();
			u = u.setScheme(S3_SCHEME);
			u = u.setHost(bucketName);
			u = u.setPath("/" + objectsKey); //$NON-NLS-1$
			return u;
		}

		@Override
		Collection<WalkRemoteObjectDatabase> getAlternates() throws IOException {
			try {
				return readAlternates(INFO_ALTERNATES);
			} catch (FileNotFoundException err) {
				// Fall through.
			}
			return null;
		}

		@Override
		WalkRemoteObjectDatabase openAlternate(final String location)
				throws IOException {
			return new DatabaseS3(bucketName, resolveKey(location));
		}

		@Override
		Collection<String> getPackNames() throws IOException {
			final HashSet<String> have = new HashSet<String>();
			have.addAll(s3.list(bucket, resolveKey("pack"))); //$NON-NLS-1$

			final Collection<String> packs = new ArrayList<String>();
			for (final String n : have) {
				if (!n.startsWith("pack-") || !n.endsWith(".pack")) //$NON-NLS-1$ //$NON-NLS-2$
					continue;

				final String in = n.substring(0, n.length() - 5) + ".idx"; //$NON-NLS-1$
				if (have.contains(in))
					packs.add(n);
			}
			return packs;
		}

		@Override
		FileStream open(final String path) throws IOException {
			final URLConnection c = s3.get(bucket, resolveKey(path));
			final InputStream raw = c.getInputStream();
			final InputStream in = s3.decrypt(c);
			final int len = c.getContentLength();
			return new FileStream(in, raw == in ? len : -1);
		}

		@Override
		void deleteFile(final String path) throws IOException {
			s3.delete(bucket, resolveKey(path));
		}

		@Override
		OutputStream writeFile(final String path,
				final ProgressMonitor monitor, final String monitorTask)
				throws IOException {
			return s3.beginPut(bucket, resolveKey(path), monitor, monitorTask);
		}

		@Override
		void writeFile(final String path, final byte[] data) throws IOException {
			s3.put(bucket, resolveKey(path), data);
		}

		Map<String, Ref> readAdvertisedRefs() throws TransportException {
			final TreeMap<String, Ref> avail = new TreeMap<String, Ref>();
			readPackedRefs(avail);
			readLooseRefs(avail);
			readRef(avail, Constants.HEAD);
			return avail;
		}

		private void readLooseRefs(final TreeMap<String, Ref> avail)
				throws TransportException {
			try {
				for (final String n : s3.list(bucket, resolveKey(ROOT_DIR
						+ "refs"))) //$NON-NLS-1$
					readRef(avail, "refs/" + n); //$NON-NLS-1$
			} catch (IOException e) {
				throw new TransportException(getURI(), JGitText.get().cannotListRefs, e);
			}
		}

		private Ref readRef(final TreeMap<String, Ref> avail, final String rn)
				throws TransportException {
			final String s;
			String ref = ROOT_DIR + rn;
			try {
				final BufferedReader br = openReader(ref);
				try {
					s = br.readLine();
				} finally {
					br.close();
				}
			} catch (FileNotFoundException noRef) {
				return null;
			} catch (IOException err) {
				throw new TransportException(getURI(), MessageFormat.format(
						JGitText.get().transportExceptionReadRef, ref), err);
			}

			if (s == null)
				throw new TransportException(getURI(), MessageFormat.format(JGitText.get().transportExceptionEmptyRef, rn));

			if (s.startsWith("ref: ")) { //$NON-NLS-1$
				final String target = s.substring("ref: ".length()); //$NON-NLS-1$
				Ref r = avail.get(target);
				if (r == null)
					r = readRef(avail, target);
				if (r == null)
					r = new ObjectIdRef.Unpeeled(Ref.Storage.NEW, target, null);
				r = new SymbolicRef(rn, r);
				avail.put(r.getName(), r);
				return r;
			}

			if (ObjectId.isId(s)) {
				final Ref r = new ObjectIdRef.Unpeeled(loose(avail.get(rn)),
						rn, ObjectId.fromString(s));
				avail.put(r.getName(), r);
				return r;
			}

			throw new TransportException(getURI(), MessageFormat.format(JGitText.get().transportExceptionBadRef, rn, s));
		}

		private Storage loose(final Ref r) {
			if (r != null && r.getStorage() == Storage.PACKED)
				return Storage.LOOSE_PACKED;
			return Storage.LOOSE;
		}

		@Override
		void close() {
			// We do not maintain persistent connections.
		}
	}
}
