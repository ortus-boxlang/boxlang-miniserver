package ortus.boxlang.web.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import org.xnio.channels.StreamSinkChannel;

public class BlockingBufferedOutputStream extends OutputStream {

	private final StreamSinkChannel	channel;
	private final ByteBuffer		buffer;

	public BlockingBufferedOutputStream( StreamSinkChannel channel ) {
		this.channel	= channel;
		this.buffer		= ByteBuffer.allocate( 1024 );
	}

	@Override
	public void write( int b ) throws IOException {
		buffer.put( ( byte ) b );
		flushBuffer();
	}

	@Override
	public void write( byte[] b, int off, int len ) throws IOException {
		int remaining = len;
		while ( remaining > 0 ) {
			int toWrite = Math.min( buffer.remaining(), remaining );
			buffer.put( b, off + ( len - remaining ), toWrite );
			remaining -= toWrite;
			flushBuffer();
		}
	}

	private void flushBuffer() throws IOException {
		if ( channel == null ) {
			buffer.clear();
			return;
		}
		buffer.flip();
		while ( buffer.hasRemaining() ) {
			channel.awaitWritable(); // Block until the channel is writable
			channel.write( buffer );
		}
		buffer.compact();
	}

	@Override
	public void flush() throws IOException {
		flushBuffer();
	}

	@Override
	public void close() throws IOException {
		flush();
		if ( channel == null ) {
			return;
		}
		channel.close();
	}
}