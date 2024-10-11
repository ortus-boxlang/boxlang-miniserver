/**
 * [BoxLang]
 *
 * Copyright [2023] [Ortus Solutions, Corp]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ortus.boxlang.web.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import org.xnio.channels.StreamSinkChannel;

/**
 * A blocking buffered output stream that writes to a StreamSinkChannel.
 * We do not use the built-in BufferedOutputStream because it does not block when the channel is not writable.
 */
public class BlockingBufferedOutputStream extends OutputStream {

	private final StreamSinkChannel	channel;
	private final ByteBuffer		buffer;

	/**
	 * Create a new BlockingBufferedOutputStream
	 *
	 * @param channel The StreamSinkChannel
	 */
	public BlockingBufferedOutputStream( StreamSinkChannel channel ) {
		this.channel	= channel;
		this.buffer		= ByteBuffer.allocate( 1024 );
	}

	/**
	 * Create a new BlockingBufferedOutputStream
	 */
	@Override
	public void write( int b ) throws IOException {
		buffer.put( ( byte ) b );
		flushBuffer();
	}

	/**
	 * Write a byte array to the output stream
	 *
	 * @param b The byte array
	 */
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

	/**
	 * Flush the buffer
	 *
	 * @throws IOException If an error occurs
	 */
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

	/**
	 * Flush the output stream
	 *
	 * @throws IOException If an error occurs
	 */
	@Override
	public void flush() throws IOException {
		flushBuffer();
	}

	/**
	 * Close the output stream
	 *
	 * @throws IOException If an error occurs
	 */
	@Override
	public void close() throws IOException {
		flush();
		if ( channel == null ) {
			return;
		}
		channel.close();
	}
}
