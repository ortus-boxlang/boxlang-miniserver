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

import java.util.Set;
import java.util.regex.Pattern;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;

/**
 * Security handler that blocks access to hidden files/directories (starting with .),
 * WEB-INF directories, BoxLang/CFML source files, dangerous HTTP methods,
 * and common config files that should never be publicly accessible.
 * This wraps the static resource handler to prevent leaking sensitive content.
 */
public class SecurityHandler implements HttpHandler {

	/**
	 * The next handler in the chain
	 */
	private final HttpHandler			nextHandler;

	/**
	 * File extensions that should never be served as static files.
	 * These are BoxLang and CFML source files that should only be executed, not downloaded.
	 */
	private static final Set<String>	BLOCKED_EXTENSIONS		= Set.of(
	    ".cfm", ".cfc", ".cfs", ".cfml",
	    ".bx", ".bxm", ".bxs"
	);

	/**
	 * HTTP methods that are disallowed. TRACE and TRACK can leak data in XSS attacks.
	 */
	private static final Set<String>	DISALLOWED_METHODS		= Set.of( "TRACE", "TRACK" );

	/**
	 * Pattern matching common config files and sensitive paths that should never be accessed.
	 */
	private static final Pattern		BLOCKED_CONFIG_PATTERN	= Pattern.compile(
	    ".*/(box\\.json|server\\.json|web\\.config|urlrewrite\\.xml|package\\.json|package-lock\\.json|Gulpfile\\.js)$",
	    Pattern.CASE_INSENSITIVE
	);

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
		String	requestPath	= exchange.getRelativePath();
		String	lowerPath	= requestPath.toLowerCase();

		// Block TRACE and TRACK HTTP methods - they can leak data in XSS attacks
		String	method		= exchange.getRequestMethod().toString().toUpperCase();
		if ( DISALLOWED_METHODS.contains( method ) ) {
			sendNotFound( exchange );
			return;
		}

		// Block access to any path that contains a segment starting with a dot
		// This includes files like /.env, /.git/config, /folder/.hidden, etc.
		// Exception: allow .well-known paths (RFC 5785)
		String[] pathSegments = requestPath.split( "/" );
		for ( String segment : pathSegments ) {
			if ( !segment.isEmpty() && segment.startsWith( "." ) && !segment.equals( ".well-known" ) ) {
				sendNotFound( exchange );
				return;
			}
		}

		// Block access to WEB-INF directories. Miniserver doesn't use these, but they may exist on the server from an old deployment.
		if ( lowerPath.contains( "/web-inf" ) ) {
			sendNotFound( exchange );
			return;
		}

		// Block common config files and sensitive paths
		if ( BLOCKED_CONFIG_PATTERN.matcher( lowerPath ).matches() ) {
			sendNotFound( exchange );
			return;
		}

		// Block BoxLang/CFML source files from being served as static content
		for ( String ext : BLOCKED_EXTENSIONS ) {
			if ( lowerPath.endsWith( ext ) ) {
				sendNotFound( exchange );
				return;
			}
		}

		// If no blocked content detected, continue to next handler
		nextHandler.handleRequest( exchange );
	}

	/**
	 * Sends a 404 Not Found response to avoid revealing that the file exists.
	 *
	 * @param exchange The HTTP exchange
	 */
	private void sendNotFound( HttpServerExchange exchange ) {
		exchange.setStatusCode( 404 );
		exchange.getResponseHeaders().put( io.undertow.util.Headers.CONTENT_TYPE, "text/plain" );
		exchange.getResponseSender().send( "Not Found" );
	}

	@Override
	public String toString() {
		return "Security Handler (Hidden File / WEB-INF / Source File Protection)";
	}
}
