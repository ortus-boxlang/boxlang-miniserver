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

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests for warmup URL functionality in MiniServer
 */
public class WarmupUrlTest {

	@Test
	void testServerConfig_warmupUrls_defaultsToEmptyList() {
		// Arrange & Act
		MiniServer.ServerConfig config = new MiniServer.ServerConfig();

		// Assert
		assertThat( config.warmupUrls ).isNotNull();
		assertThat( config.warmupUrls ).isEmpty();
	}

	@Test
	void testServerConfig_warmupUrls_canAddMultipleUrls() {
		// Arrange
		MiniServer.ServerConfig config = new MiniServer.ServerConfig();

		// Act
		config.warmupUrls.add( "/app/init" );
		config.warmupUrls.add( "/health" );
		config.warmupUrls.add( "http://localhost:8080/cache/warm" );

		// Assert
		assertThat( config.warmupUrls ).hasSize( 3 );
		assertThat( config.warmupUrls ).containsExactly( "/app/init", "/health", "http://localhost:8080/cache/warm" );
	}

	@Test
	void testServerConfig_warmupUrls_isModifiable() {
		// Arrange
		MiniServer.ServerConfig config = new MiniServer.ServerConfig();

		// Act
		config.warmupUrls.add( "/test1" );
		config.warmupUrls.add( "/test2" );
		config.warmupUrls.remove( "/test1" );

		// Assert
		assertThat( config.warmupUrls ).hasSize( 1 );
		assertThat( config.warmupUrls ).contains( "/test2" );
	}

	@Test
	void testServerConfig_warmupUrls_canBeReassigned() {
		// Arrange
		MiniServer.ServerConfig	config		= new MiniServer.ServerConfig();

		// Act
		List<String>			customUrls	= new ArrayList<>();
		customUrls.add( "/custom1" );
		customUrls.add( "/custom2" );
		config.warmupUrls = customUrls;

		// Assert
		assertThat( config.warmupUrls ).hasSize( 2 );
		assertThat( config.warmupUrls ).containsExactly( "/custom1", "/custom2" );
	}
}
