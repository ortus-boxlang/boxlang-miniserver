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
package ortus.boxlang.web;

import static com.google.common.truth.Truth.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for MiniServer metrics infrastructure — server instance storage,
 * active request counting, MetricsHandler, and XnioWorker accessors.
 */
public class MiniServerTest {

	// =========================================================================
	// Server instance storage
	// =========================================================================

	@DisplayName( "getServerInstance() returns null before server start" )
	@Test
	public void testGetServerInstanceReturnsNull() {
		assertThat( MiniServer.getServerInstance() ).isNull();
	}

	// =========================================================================
	// XnioWorker accessor
	// =========================================================================

	@DisplayName( "getWorker() returns null before server start" )
	@Test
	public void testGetWorkerReturnsNull() {
		assertThat( MiniServer.getWorker() ).isNull();
	}

	// =========================================================================
	// Active request counter
	// =========================================================================

	@DisplayName( "getActiveRequests() is zero when no requests are processing" )
	@Test
	public void testGetActiveRequestsStartsAtZero() {
		assertThat( MiniServer.getActiveRequests() ).isEqualTo( 0 );
	}

	// =========================================================================
	// MetricsHandler accessor
	// =========================================================================

	@DisplayName( "getMetricsHandler() returns null before server start" )
	@Test
	public void testGetMetricsHandlerReturnsNull() {
		assertThat( MiniServer.getMetricsHandler() ).isNull();
	}

	// =========================================================================
	// Exchange tracking
	// =========================================================================

	@DisplayName( "getCurrentExchange() returns null outside a request" )
	@Test
	public void testGetCurrentExchangeReturnsNullOutsideRequest() {
		assertThat( MiniServer.getCurrentExchange() ).isNull();
	}

	// =========================================================================
	// Exposed public statics
	// =========================================================================

	@DisplayName( "shuttingDown flag defaults to false" )
	@Test
	public void testShuttingDownDefaultsToFalse() {
		assertThat( MiniServer.shuttingDown ).isFalse();
	}

	@DisplayName( "getWebsocketHandler() works on the static field" )
	@Test
	public void testWebsocketHandlerGetter() {
		// The field is public and the getter returns it — just verify the getter exists
		MiniServer.getWebsocketHandler();
	}
}
