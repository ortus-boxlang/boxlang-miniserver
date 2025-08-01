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

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;

/**
 * Security handler that blocks access to hidden files and directories (starting with .)
 * This prevents access to sensitive files like .env, .git/config, .htaccess, etc.
 */
public class SecurityHandler implements HttpHandler {

	/**
	 * The next handler in the chain
	 */
	private final HttpHandler nextHandler;

	/**
	 * Constructor
	 *
	 * @param nextHandler The next handler in the chain
	 */
	public SecurityHandler( HttpHandler nextHandler ) {
		this.nextHandler = nextHandler;
	}

	@Override
	public void handleRequest( final HttpServerExchange exchange ) throws Exception {
		String		requestPath		= exchange.getRequestPath();

		// Block access to any path that contains a segment starting with a dot
		// This includes files like /.env, /.git/config, /folder/.hidden, etc.
		String[]	pathSegments	= requestPath.split( "/" );
		for ( String segment : pathSegments ) {
			if ( segment.startsWith( "." ) && !segment.isEmpty() ) {
				// Return 404 Not Found for security reasons (don't reveal that the file exists)
				exchange.setStatusCode( 404 );
				exchange.getResponseHeaders().put( io.undertow.util.Headers.CONTENT_TYPE, "text/plain" );
				exchange.getResponseSender().send( "Not Found" );
				return;
			}
		}

		// If no hidden files/directories detected, continue to next handler
		nextHandler.handleRequest( exchange );
	}

	@Override
	public String toString() {
		return "Security Handler (Hidden File Protection)";
	}
}
