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
package ortus.boxlang.web.handlers;

import java.io.IOException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.xnio.ChannelListener;
import org.xnio.ChannelListeners;
import org.xnio.channels.StreamSinkChannel;

import io.undertow.predicate.Predicate;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import ortus.boxlang.web.WebRequestExecutor;
import ortus.boxlang.web.exchange.BoxHTTPUndertowExchange;

/**
 * Undertow HttpHandler for BoxLang
 * This mini-server only has one web root for all requests
 */
public class BLHandler implements HttpHandler {

	/**
	 * The pattern to match the path info
	 */
	static final Pattern	pattern	= Pattern.compile( "^(/.+?\\.cfml|/.+?\\.cf[cms]|.+?\\.bx[ms]{0,1})(/.*)?$" );

	/**
	 * The web root
	 */
	private String			webRoot;

	/**
	 * Create a new BLHandler
	 *
	 * @param webRoot The web root
	 */
	public BLHandler( String webRoot ) {
		this.webRoot = webRoot;
	}

	/**
	 * Handle the request
	 *
	 * @param exchange The HttpServerExchange
	 *
	 * @throws Exception If an error occurs
	 */
	@Override
	public void handleRequest( io.undertow.server.HttpServerExchange exchange ) throws Exception {
		if ( exchange.isInIoThread() ) {
			exchange.dispatch( this );
			return;
		}
		exchange.startBlocking();

		processPathInfo( exchange );
		BoxHTTPUndertowExchange httpExchange = new BoxHTTPUndertowExchange( exchange );
		// In our custom pure Undertow server, we need to track our own FR transactions
		WebRequestExecutor.execute( httpExchange, this.webRoot, true );

		finalizeResponse( httpExchange );

	}

	/**
	 * Process path info real quick
	 * Path info is sort of a servlet concept. It's just everything left in the URI that didn't match the servlet mapping
	 * In undertow, we can use predicates to match the path info and store it in the exchange attachment so we can get it in the CGI scope
	 *
	 * @param exchange The HttpServerExchange
	 */
	private void processPathInfo( HttpServerExchange exchange ) {
		String				requestPath			= exchange.getRequestURI();
		Map<String, Object>	predicateContext	= exchange.getAttachment( Predicate.PREDICATE_CONTEXT );
		if ( !predicateContext.containsKey( "pathInfo" ) ) {
			Matcher matcher = pattern.matcher( requestPath );
			if ( matcher.find() ) {
				// Use the second capture group if it exists to set the path info
				String pathInfo = matcher.group( 2 );
				if ( pathInfo != null ) {
					exchange.setRelativePath( matcher.group( 1 ) );
					predicateContext.put( "pathInfo", pathInfo );
				} else {
					predicateContext.put( "pathInfo", "" );
				}
			} else {
				predicateContext.put( "pathInfo", "" );
			}
		}
	}

	/**
	 * Finalize the response
	 *
	 * @param httpExchange The BoxHTTPUndertowExchange
	 */
	public void finalizeResponse( BoxHTTPUndertowExchange httpExchange ) {
		StreamSinkChannel channel = httpExchange.getResponseChannel();

		channel.getWriteSetter().set( new ChannelListener<StreamSinkChannel>() {

			@Override
			public void handleEvent( StreamSinkChannel channel ) {
				try {
					// Shutdown writes after data is written
					channel.shutdownWrites();

					// Ensure the channel is flushed
					if ( !channel.flush() ) {
						// If not flushed, set a listener to complete the flushing
						channel.getWriteSetter().set( ChannelListeners.flushingChannelListener( new ChannelListener<StreamSinkChannel>() {

							@Override
							public void handleEvent( StreamSinkChannel channel ) {
								try {
									if ( channel.flush() ) {
										// End the exchange after flushing is complete
										httpExchange.getExchange().endExchange();
									}
								} catch ( IOException e ) {
									// End the exchange in case of an error
									httpExchange.getExchange().endExchange();
								}
							}
						}, ChannelListeners.closingChannelExceptionHandler() ) );
						// Resume writes to complete the flush
						channel.resumeWrites();
					} else {
						// If flushed immediately, end the exchange
						httpExchange.getExchange().endExchange();
					}
				} catch ( IOException e ) {
					// End the exchange in case of an error
					httpExchange.getExchange().endExchange();
				}
			}
		} );

		// Resume writes to trigger the listener
		channel.resumeWrites();
	}

}
