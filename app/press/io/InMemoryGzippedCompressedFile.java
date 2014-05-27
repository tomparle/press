package press.io;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPOutputStream;

import play.exceptions.UnexpectedException;
import play.mvc.Http.Response;

/**
 * A gzipped version of the memory compressed file
 */
public class InMemoryGzippedCompressedFile extends InMemoryCompressedFile {

	public InMemoryGzippedCompressedFile(String fileKey) {
		super(fileKey);
	}

	@Override
	protected byte[] getCompressedData() {
		try {
			final ByteArrayOutputStream gzippedResponse = gzip(outputStream.toByteArray());
			gzippedResponse.close();
			return gzippedResponse.toByteArray();
		} catch (final IOException e) {
			throw new UnexpectedException(e);
		}
	}

	@Override
	public void configureResponse(Response response) {
		response.setHeader("Content-Encoding", "gzip");
		response.setHeader("Content-Length", String.valueOf(length()));
	}

	private static ByteArrayOutputStream gzip(final byte[] input) throws IOException {
		final InputStream inputStream = new ByteArrayInputStream(input);
		final ByteArrayOutputStream stringOutputStream = new ByteArrayOutputStream((int) (input.length * 0.75));
		final OutputStream gzipOutputStream = new GZIPOutputStream(stringOutputStream);
		final byte[] buf = new byte[4096];
		int len;
		while ((len = inputStream.read(buf)) > 0) {
			gzipOutputStream.write(buf, 0, len);
		}
		inputStream.close();
		gzipOutputStream.close();
		return stringOutputStream;
	}

}
