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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.undertow.io.Sender;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.HttpString;

/**
 * Tests for HealthCheckHandler routing and response structure.
 */
public class HealthCheckHandlerTest {

	private HttpHandler			nextHandler;
	private HealthCheckHandler	handler;

	@BeforeEach
	void setUp() {
		nextHandler	= mock( HttpHandler.class );
		handler		= new HealthCheckHandler( nextHandler, false );
	}

	// =========================================================================
	// Route matching
	// =========================================================================

	@DisplayName( "GET /health is handled by HealthCheckHandler" )
	@Test
	void testHealthEndpointRoutedCorrectly() throws Exception {
		HttpServerExchange exchange = createExchange( "/health" );

		handler.handleRequest( exchange );

		verify( nextHandler, never() ).handleRequest( any() );
	}

	@DisplayName( "GET /health/undertow falls through to next handler" )
	@Test
	void testUndertowMetricsPathFallsThroughToNext() throws Exception {
		HttpServerExchange exchange = createExchange( "/health/undertow" );

		handler.handleRequest( exchange );

		verify( nextHandler ).handleRequest( exchange );
	}

	@DisplayName( "Non-health paths are passed to the next handler" )
	@Test
	void testNonHealthPathDelegatesToNext() throws Exception {
		HttpServerExchange exchange = createExchange( "/some/other/path" );

		handler.handleRequest( exchange );

		verify( nextHandler ).handleRequest( exchange );
	}

	@DisplayName( "GET /health/undertow/ with trailing slash still falls through" )
	@Test
	void testUndertowMetricsWithTrailingSlashFallsThrough() throws Exception {
		HttpServerExchange exchange = createExchange( "/health/undertow/" );

		handler.handleRequest( exchange );

		// Trailing slash does not match the exact path — falls through to next handler
		verify( nextHandler ).handleRequest( exchange );
	}

	// =========================================================================
	// Response structure (server not started — graceful nulls)
	// =========================================================================

	@DisplayName( "Health endpoint returns runtime, undertow, and websocket JSON sections" )
	@Test
	void testHealthResponseIncludesRuntimeAndMetricsSections() throws Exception {
		StringBuilder		capturedBody	= new StringBuilder();

		HttpServerExchange	exchange		= createCapturingExchange( "/health", capturedBody );

		handler.handleRequest( exchange );

		assertThat( exchange.getStatusCode() ).isEqualTo( 200 );
		String body = capturedBody.toString();
		assertThat( body ).contains( "\"status\"" );
		assertThat( body ).contains( "\"uptime\"" );
		assertThat( body ).contains( "\"memoryUsed\"" );
		assertThat( body ).contains( "\"activeRequests\"" );
		assertThat( body ).contains( "\"workerPool\"" );
		assertThat( body ).contains( "\"listeners\"" );
		assertThat( body ).contains( "\"requestMetrics\"" );
		assertThat( body ).contains( "\"websocket\"" );
	}

	// =========================================================================
	// Secure mode
	// =========================================================================

	@DisplayName( "In secure mode, non-localhost requests to /health return basic status only" )
	@Test
	void testSecureModeForNonLocalhostHealthIsRedacted() throws Exception {
		HealthCheckHandler	secureHandler	= new HealthCheckHandler( nextHandler, true );

		StringBuilder		capturedBody	= new StringBuilder();

		HttpServerExchange	exchange		= createCapturingExchange( "/health", capturedBody );
		when( exchange.getSourceAddress() ).thenReturn( new InetSocketAddress( "10.10.10.10", 12345 ) );

		secureHandler.handleRequest( exchange );

		assertThat( exchange.getStatusCode() ).isEqualTo( 200 );
		assertThat( capturedBody.toString() ).contains( "\"status\": \"UP\"" );
		assertThat( capturedBody.toString() ).doesNotContain( "\"workerPool\"" );
	}

	// =========================================================================
	// Helpers
	// =========================================================================

	/**
	 * Creates a mocked {@link HttpServerExchange} for the given request path using the GET method.
	 *
	 * @param requestPath the request path to mock
	 * @return a mocked exchange with GET method and default status code (200)
	 */
	private HttpServerExchange createExchange( String requestPath ) {
		return createExchange( requestPath, "GET" );
	}

	/**
	 * Creates a fully mocked {@link HttpServerExchange} with the specified request path and HTTP method.
	 * The exchange is configured with:
	 * <ul>
	 *   <li>Mocked request path and method</li>
	 *   <li>Empty response headers</li>
	 *   <li>Mocked response sender</li>
	 *   <li>Status code tracking (defaults to 200, captures changes via setStatusCode)</li>
	 * </ul>
	 *
	 * @param requestPath the request path to mock
	 * @param method the HTTP method (GET, POST, etc.)
	 * @return a mocked exchange ready for handler testing
	 */
	private HttpServerExchange createExchange( String requestPath, String method ) {
		HttpServerExchange exchange = mock( HttpServerExchange.class );
		when( exchange.getRequestPath() ).thenReturn( requestPath );
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

	/**
	 * Creates a mocked {@link HttpServerExchange} that captures response body content.
	 * The exchange's {@link Sender} is configured to append all sent strings to the provided
	 * {@link StringBuilder}, allowing tests to inspect response content.
	 *
	 * @param requestPath the request path to mock
	 * @param bodyCapture a StringBuilder to accumulate response body content
	 * @return a mocked exchange with body-capturing sender
	 */
	private HttpServerExchange createCapturingExchange( String requestPath, StringBuilder bodyCapture ) {
		HttpServerExchange	exchange	= createExchange( requestPath );
		Sender				sender		= mock( Sender.class );
		doAnswer( invocation -> {
			bodyCapture.append( invocation.getArgument( 0, String.class ) );
			return null;
		} ).when( sender ).send( any( String.class ), any( java.nio.charset.Charset.class ) );
		when( exchange.getResponseSender() ).thenReturn( sender );
		return exchange;
	}
}
