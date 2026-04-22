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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
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

	/** A resolved alias entry: URL prefix to filesystem path string. */
	private static final class AliasEntry {

		final String urlPrefix;
		final String targetRoot;

		AliasEntry( String prefix, String target ) {
			// Normalize: add leading slash, strip trailing slash
			String p = prefix == null ? "/" : prefix.trim();
			if ( !p.startsWith( "/" ) ) p = "/" + p;
			if ( p.length() > 1 && p.endsWith( "/" ) ) p = p.substring( 0, p.length() - 1 );
			this.urlPrefix  = p;
			this.targetRoot = target;
		}
	}

	/**
	 * The web root
	 */
	private final String			webRoot;

	/** Alias entries sorted longest-prefix-first. Never null; may be empty. */
	private final List<AliasEntry>	sortedAliases;

	/**
	 * Create a new BLHandler with no aliases.
	 *
	 * @param webRoot The web root
	 */
	public BLHandler( String webRoot ) {
		this( webRoot, Collections.emptyMap() );
	}

	/**
	 * Create a new BLHandler with folder alias support.
	 *
	 * @param webRoot URL-prefix to absolute filesystem-path alias mappings
	 * @param aliases URL-prefix to absolute filesystem-path alias mappings
	 */
	public BLHandler( String webRoot, Map<String, String> aliases ) {
		this.webRoot = webRoot;
		List<AliasEntry> list = new ArrayList<>();
		if ( aliases != null ) {
			for ( Map.Entry<String, String> e : aliases.entrySet() ) {
				if ( e.getKey() != null && e.getValue() != null ) {
					list.add( new AliasEntry( e.getKey(), e.getValue() ) );
				}
			}
		}
		// Longest prefix first so /docs/api beats /docs when both configured
		list.sort( Comparator.comparingInt( ( AliasEntry e ) -> e.urlPrefix.length() ).reversed() );
		this.sortedAliases = Collections.unmodifiableList( list );
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

		// Resolve the effective webRoot and adjust relativePath for alias matches
		String effectiveWebRoot = this.webRoot;
		if ( !sortedAliases.isEmpty() ) {
			String relativePath = exchange.getRelativePath();
			for ( AliasEntry alias : sortedAliases ) {
				if ( matchesAliasPrefix( relativePath, alias.urlPrefix ) ) {
					String aliasRelative = relativePath.substring( alias.urlPrefix.length() );
					if ( aliasRelative.isEmpty() ) {
						aliasRelative = "/";
					}
					exchange.setRelativePath( aliasRelative );
					effectiveWebRoot = alias.targetRoot;
					break;
				}
			}
		}

		BoxHTTPUndertowExchange httpExchange = new BoxHTTPUndertowExchange( exchange );
		// In our custom pure Undertow server, we need to track our own FR transactions
		WebRequestExecutor.execute( httpExchange, effectiveWebRoot, true );

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
		String				requestPath		= exchange.getRequestURI();
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

	/**
	 * True if {@code path} starts with {@code prefix} at a path-segment boundary.
	 * Prevents "/documentation" from matching the alias "/docs".
	 */
	private static boolean matchesAliasPrefix( String path, String prefix ) {
		if ( prefix == null || prefix.isEmpty() || prefix.equals( "/" ) ) {
			return true;
		}
		if ( !path.startsWith( prefix ) ) {
			return false;
		}
		if ( path.length() == prefix.length() ) {
			return true;
		}
		return path.charAt( prefix.length() ) == '/';
	}

}
