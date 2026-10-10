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
package ortus.boxlang.web.exchange;

import static com.google.common.truth.Truth.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.undertow.Undertow;
import io.undertow.server.HttpServerExchange;

/**
 * BL-2744: getRequestBody() must wait for a body that arrives after the headers.
 * Uses a real Undertow server on a loopback port and a raw socket client.
 */
public class BoxHTTPUndertowExchangeBodyTest {

	private Undertow					server;
	private int							port;
	private final AtomicReference<Object>	body		= new AtomicReference<>();
	private final AtomicReference<Object>	secondBody	= new AtomicReference<>();

	@BeforeEach
	void setUp() {
		server = Undertow.builder()
		    .addHttpListener( 0, "127.0.0.1" )
		    .setHandler( ( HttpServerExchange exchange ) -> {
			    if ( exchange.isInIoThread() ) {
				    exchange.dispatch( this::handleBlocking );
				    return;
			    }
			    handleBlocking( exchange );
		    } )
		    .build();
		server.start();
		port = ( ( InetSocketAddress ) server.getListenerInfo().get( 0 ).getAddress() ).getPort();
	}

	private void handleBlocking( HttpServerExchange exchange ) {
		exchange.startBlocking();
		BoxHTTPUndertowExchange boxExchange = new BoxHTTPUndertowExchange( exchange );
		body.set( boxExchange.getRequestBody() );
		secondBody.set( boxExchange.getRequestBody() );
		exchange.getResponseSender().send( "ok" );
	}

	@AfterEach
	void tearDown() {
		server.stop();
	}

	/**
	 * Sends the raw request pieces, pausing between each, and waits for the response.
	 */
	private void send( int pauseMillis, byte[]... pieces ) throws Exception {
		try ( Socket socket = new Socket( "127.0.0.1", port ) ) {
			socket.setSoTimeout( 10000 );
			OutputStream out = socket.getOutputStream();
			for ( int i = 0; i < pieces.length; i++ ) {
				if ( i > 0 ) {
					Thread.sleep( pauseMillis );
				}
				out.write( pieces[ i ] );
				out.flush();
			}
			InputStream			in		= socket.getInputStream();
			ByteArrayOutputStream	response	= new ByteArrayOutputStream();
			byte[]				buf		= new byte[ 1024 ];
			int					read;
			try {
				while ( ( read = in.read( buf ) ) != -1 ) {
					response.write( buf, 0, read );
					if ( response.toString( StandardCharsets.UTF_8 ).endsWith( "ok" ) ) {
						break;
					}
				}
			} catch ( IOException e ) {
				// handled by the assertions on body
			}
		}
	}

	private static byte[] ascii( String s ) {
		return s.getBytes( StandardCharsets.UTF_8 );
	}

	@Test
	void testGetRequestBody_lateBodyWithContentLength_returnsFullBody() throws Exception {
		String payload = "{\"jsonrpc\":\"2.0\",\"method\":\"ping\"}";
		send( 150,
		    ascii( "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: " + payload.length() + "\r\nConnection: close\r\n\r\n" ),
		    ascii( payload ) );
		assertThat( body.get() ).isEqualTo( payload );
	}

	@Test
	void testGetRequestBody_lateChunkedBody_returnsFullBody() throws Exception {
		String payload = "hello chunked world";
		send( 150,
		    ascii( "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Type: text/plain\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n" ),
		    ascii( Integer.toHexString( payload.length() ) + "\r\n" + payload + "\r\n0\r\n\r\n" ) );
		assertThat( body.get() ).isEqualTo( payload );
	}

	@Test
	void testGetRequestBody_bodyInSameWriteAsHeaders_returnsFullBody() throws Exception {
		String payload = "immediate";
		send( 0, ascii( "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Type: text/plain\r\nContent-Length: 9\r\nConnection: close\r\n\r\n" + payload ) );
		assertThat( body.get() ).isEqualTo( payload );
	}

	@Test
	void testGetRequestBody_noBody_returnsEmptyString() throws Exception {
		send( 0, ascii( "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\nConnection: close\r\n\r\n" ) );
		assertThat( body.get() ).isEqualTo( "" );
	}

	@Test
	void testGetRequestBody_getRequest_returnsEmptyString() throws Exception {
		send( 0, ascii( "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n" ) );
		assertThat( body.get() ).isEqualTo( "" );
	}

	@Test
	void testGetRequestBody_alreadyRead_returnsEmptyString() throws Exception {
		send( 0, ascii( "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Type: text/plain\r\nContent-Length: 4\r\nConnection: close\r\n\r\nabcd" ) );
		assertThat( body.get() ).isEqualTo( "abcd" );
		assertThat( secondBody.get() ).isEqualTo( "" );
	}

	@Test
	void testGetRequestBody_textContentType_returnsString() throws Exception {
		send( 100,
		    ascii( "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n" ),
		    ascii( "{}" ) );
		assertThat( body.get() ).isInstanceOf( String.class );
	}

	@Test
	void testGetRequestBody_binaryContentType_returnsByteArray() throws Exception {
		send( 100,
		    ascii( "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/octet-stream\r\nContent-Length: 3\r\nConnection: close\r\n\r\n" ),
		    new byte[] { 1, 2, 3 } );
		assertThat( body.get() ).isInstanceOf( byte[].class );
		assertThat( ( byte[] ) body.get() ).isEqualTo( new byte[] { 1, 2, 3 } );
	}

	@Test
	void testGetRequestBody_noContentTypeAndNoBody_returnsEmptyString() throws Exception {
		send( 0, ascii( "POST / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\nConnection: close\r\n\r\n" ) );
		assertThat( body.get() ).isEqualTo( "" );
	}
}
