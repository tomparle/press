package press.io;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.util.HashSet;
import java.util.Set;

import play.cache.Cache;
import play.exceptions.UnexpectedException;
import press.PluginConfig;
import press.PressException;
import press.PressLogger;

public class InMemoryCompressedFile extends CompressedFile {
	private static final String FILE_LIST_KEY = "InMemoryFileList";
	private InputStream inputStream;
	private Writer writer;
	private ByteArrayOutputStream outputStream;
	private byte[] bytes;
	private static final String A_VERY_LONG_TIME = "30d";

	public InMemoryCompressedFile(String fileKey) {
		super(fileKey);
	}

	@Override
	public boolean exists() {
		if (inputStream != null) {
			return true;
		}

		final long startTime = System.currentTimeMillis();
		bytes = (byte[]) Cache.get(getCacheKey());
		if (bytes != null) {
			final long totalTime = System.currentTimeMillis() - startTime;
			PressLogger.trace("Got file of size %d bytes from cache in %d milli-seconds.", bytes.length, totalTime);
			inputStream = new ByteArrayInputStream(bytes);
			return true;
		}

		return false;
	}

	@Override
	public InputStream inputStream() {
		if (!exists()) {
			throw new PressException("Can't create InputStream. File with key " + getCacheKey() + " does not exist in cache");
		}

		return inputStream;
	}

	@Override
	public String name() {
		return FileIO.getFileNameFromPath(getFileKey());
	}

	@Override
	public Writer startWrite() {
		// Compression might take a while, so if we're already writing out the
		// compressed file from a different thread, wait for it to finish and return null (=no writer)
		final String inProgressKey = getInProgressKey(getFileKey());

		// file already being generated
		if (Cache.get(inProgressKey) != null) {
			final long now = System.currentTimeMillis();
			while (Cache.get(inProgressKey) != null) {
				PressLogger.trace("Waiting for file to be generated: " + getFileKey());
				if (System.currentTimeMillis() - now > PluginConfig.maxCompressionTimeMillis) {
					throw new PressException("Timeout waiting for compressed file to be generated: " + inProgressKey);
				}
				try {
					Thread.sleep(1000);
				} catch (final InterruptedException e) {
				}
			}
			// return null to notify that no writer is used and the generated file is already ready to serve
			return null;
		}
		final String expiration = (PluginConfig.maxCompressionTimeMillis / 1000) + "s";
		Cache.safeSet(inProgressKey, true, expiration);

		if (writer == null) {
			outputStream = new ByteArrayOutputStream();
			try {
				writer = new BufferedWriter(new OutputStreamWriter(outputStream, "UTF-8"));
			} catch (final UnsupportedEncodingException e) {
				throw new UnexpectedException(e);
			}
		}

		return writer;
	}

	@Override
	public void close() {
		if (writer == null) {
			throw new PressException("Output stream has not yet been created. Call getWriter() and write to it.");
		}
		try {
			writer.flush();
			writer.close();
		} catch (final IOException e) {
			throw new UnexpectedException(e);
		}

		final byte[] outBytes = outputStream.toByteArray();
		PressLogger.trace("Saving file of size %d bytes to cache.", outBytes.length);
		addFileToCache(getFileKey(), outBytes);

		final String inProgressKey = getInProgressKey(getFileKey());
		Cache.safeDelete(inProgressKey);
	}

	private static String getInProgressKey(String fileKey) {
		return "in-progress-" + fileKey;
	}

	private String getCacheKey() {
		return getCacheKey(getFileKey());
	}

	private static String getCacheKey(String fileKey) {
		return "file-" + fileKey;
	}

	private void addFileToCache(String fileKey, byte[] outBytes) {
		final long startTime = System.currentTimeMillis();

		final Set<String> fileList = getFileList();
		fileList.add(fileKey);
		Cache.set(FILE_LIST_KEY, fileList, A_VERY_LONG_TIME);

		final String cacheKey = getCacheKey(fileKey);
		if (!Cache.safeSet(cacheKey, outBytes, A_VERY_LONG_TIME)) {
			throw new PressException("Underlying cache implementation could not store compressed file " + fileKey + " in cache");
		}

		inputStream = null;
		bytes = null;

		final long totalTime = System.currentTimeMillis() - startTime;
		PressLogger.trace("Saved file to cache in %d milli-seconds", totalTime);
	}

	public static int clearMemoryCache(String extension) {
		final Set<String> files = getFileList();
		for (final String fileKey : files) {
			Cache.delete(getCacheKey(fileKey));
			Cache.delete(getInProgressKey(fileKey));
		}
		Cache.delete(FILE_LIST_KEY);
		return files.size();
	}

	private static Set<String> getFileList() {
		Set<String> fileList = (Set<String>) Cache.get(FILE_LIST_KEY);
		if (fileList == null) {
			fileList = new HashSet<String>();
		}
		return fileList;
	}

	@Override
	public long length() {
		if (!exists()) {
			throw new PressException("Can't get length. File with key " + getCacheKey() + " does not exist in cache");
		}

		return bytes.length;
	}
}

