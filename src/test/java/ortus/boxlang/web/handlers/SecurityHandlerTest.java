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

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.undertow.io.Sender;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.HttpString;

/**
 * Tests for SecurityHandler — hidden files, WEB-INF, and source file extension blocking.
 */
public class SecurityHandlerTest {

	private HttpHandler		nextHandler;
	private SecurityHandler	securityHandler;

	@BeforeEach
	void setUp() {
		nextHandler		= mock( HttpHandler.class );
		securityHandler	= new SecurityHandler( nextHandler );
	}

	/**
	 * Helper to create a mock exchange with the given request path and GET method.
	 */
	private HttpServerExchange createExchange( String requestPath ) {
		return createExchange( requestPath, "GET" );
	}

	/**
	 * Helper to create a mock exchange with the given request path and HTTP method.
	 */
	private HttpServerExchange createExchange( String requestPath, String method ) {
		HttpServerExchange exchange = mock( HttpServerExchange.class );
		when( exchange.getRelativePath() ).thenReturn( requestPath );
		when( exchange.getRequestMethod() ).thenReturn( new HttpString( method ) );
		HeaderMap responseHeaders = new HeaderMap();
		when( exchange.getResponseHeaders() ).thenReturn( responseHeaders );
		when( exchange.getResponseSender() ).thenReturn( mock( Sender.class ) );
		final int[] statusCode = { 200 };
		doAnswer( invocation -> {
			statusCode[ 0 ] = invocation.getArgument( 0 );
			return exchange;
		} ).when( exchange ).setStatusCode( anyInt() );
		when( exchange.getStatusCode() ).thenAnswer( invocation -> statusCode[ 0 ] );
		return exchange;
	}

	// -------------------------------------------------------------------------
	// Hidden file/directory tests
	// -------------------------------------------------------------------------

	@ParameterizedTest
	@ValueSource( strings = { "/.env", "/.git/config", "/.htaccess", "/folder/.hidden", "/.DS_Store", "/.boxlang.json" } )
	void testSecurityHandler_hiddenFile_returnsNotFound( String path ) throws Exception {
		HttpServerExchange exchange = createExchange( path );

		securityHandler.handleRequest( exchange );

		assertThat( exchange.getStatusCode() ).isEqualTo( 404 );
		verify( nextHandler, never() ).handleRequest( exchange );
	}

	// -------------------------------------------------------------------------
	// WEB-INF tests
	// -------------------------------------------------------------------------

	@ParameterizedTest
	@ValueSource( strings = { "/WEB-INF/web.xml", "/web-inf/classes/foo.class", "/WEB-INF/", "/app/WEB-INF/config.xml" } )
	void testSecurityHandler_webInf_returnsNotFound( String path ) throws Exception {
		HttpServerExchange exchange = createExchange( path );

		securityHandler.handleRequest( exchange );

		assertThat( exchange.getStatusCode() ).isEqualTo( 404 );
		verify( nextHandler, never() ).handleRequest( exchange );
	}

	// -------------------------------------------------------------------------
	// Blocked source file extension tests
	// -------------------------------------------------------------------------

	@ParameterizedTest
	@ValueSource( strings = { "/index.cfm", "/app.cfc", "/script.cfs", "/page.cfml", "/index.bx", "/page.bxm", "/script.bxs" } )
	void testSecurityHandler_sourceFileExtensions_returnsNotFound( String path ) throws Exception {
		HttpServerExchange exchange = createExchange( path );

		securityHandler.handleRequest( exchange );

		assertThat( exchange.getStatusCode() ).isEqualTo( 404 );
		verify( nextHandler, never() ).handleRequest( exchange );
	}

	@ParameterizedTest
	@ValueSource( strings = { "/sub/dir/index.CFM", "/APP.CFC", "/SCRIPT.BXS" } )
	void testSecurityHandler_sourceFileExtensions_caseInsensitive_returnsNotFound( String path ) throws Exception {
		HttpServerExchange exchange = createExchange( path );

		securityHandler.handleRequest( exchange );

		assertThat( exchange.getStatusCode() ).isEqualTo( 404 );
		verify( nextHandler, never() ).handleRequest( exchange );
	}

	// -------------------------------------------------------------------------
	// Allowed requests pass through
	// -------------------------------------------------------------------------

	@ParameterizedTest
	@ValueSource( strings = { "/index.html", "/styles.css", "/app.js", "/images/logo.png", "/api/data.json", "/favicon.ico" } )
	void testSecurityHandler_allowedStaticFiles_passThrough( String path ) throws Exception {
		HttpServerExchange exchange = createExchange( path );

		securityHandler.handleRequest( exchange );

		verify( nextHandler ).handleRequest( exchange );
	}

	@Test
	void testSecurityHandler_rootPath_passThrough() throws Exception {
		HttpServerExchange exchange = createExchange( "/" );

		securityHandler.handleRequest( exchange );

		verify( nextHandler ).handleRequest( exchange );
	}

	// -------------------------------------------------------------------------
	// Disallowed HTTP methods (TRACE/TRACK)
	// -------------------------------------------------------------------------

	@ParameterizedTest
	@ValueSource( strings = { "TRACE", "TRACK", "trace", "Track" } )
	void testSecurityHandler_disallowedMethods_returnsNotFound( String method ) throws Exception {
		HttpServerExchange exchange = createExchange( "/index.html", method );

		securityHandler.handleRequest( exchange );

		assertThat( exchange.getStatusCode() ).isEqualTo( 404 );
		verify( nextHandler, never() ).handleRequest( exchange );
	}

	@ParameterizedTest
	@ValueSource( strings = { "GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS" } )
	void testSecurityHandler_allowedMethods_passThrough( String method ) throws Exception {
		HttpServerExchange exchange = createExchange( "/index.html", method );

		securityHandler.handleRequest( exchange );

		verify( nextHandler ).handleRequest( exchange );
	}

	// -------------------------------------------------------------------------
	// Blocked config file tests
	// -------------------------------------------------------------------------

	@ParameterizedTest
	@ValueSource( strings = {
	    "/box.json", "/server.json", "/web.config", "/urlrewrite.xml",
	    "/package.json", "/package-lock.json", "/Gulpfile.js",
	    "/sub/dir/box.json", "/app/server.json", "/BOX.JSON", "/Server.Json"
	} )
	void testSecurityHandler_blockedConfigFiles_returnsNotFound( String path ) throws Exception {
		HttpServerExchange exchange = createExchange( path );

		securityHandler.handleRequest( exchange );

		assertThat( exchange.getStatusCode() ).isEqualTo( 404 );
		verify( nextHandler, never() ).handleRequest( exchange );
	}

	// -------------------------------------------------------------------------
	// .well-known exception
	// -------------------------------------------------------------------------

	@ParameterizedTest
	@ValueSource( strings = { "/.well-known/acme-challenge/token", "/.well-known/openid-configuration" } )
	void testSecurityHandler_wellKnown_passThrough( String path ) throws Exception {
		HttpServerExchange exchange = createExchange( path );

		securityHandler.handleRequest( exchange );

		verify( nextHandler ).handleRequest( exchange );
	}

	// -------------------------------------------------------------------------
	// toString
	// -------------------------------------------------------------------------

	@Test
	void testSecurityHandler_toString() {
		assertThat( securityHandler.toString() ).contains( "Security Handler" );
	}
}
