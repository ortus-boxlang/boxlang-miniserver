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
package ortus.boxlang.web.config;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Tests for MiniServerConfig — configuration parsing, defaults, and JSON loading.
 */
public class MiniServerConfigTest {

	// -------------------------------------------------------------------------
	// Static default map tests
	// -------------------------------------------------------------------------

	@Test
	void testDefaultUndertowOptions_containsMaxEntitySize() {
		assertThat( MiniServerConfig.DEFAULT_UNDERTOW_OPTIONS ).containsKey( "MAX_ENTITY_SIZE" );
	}

	@Test
	void testDefaultUndertowOptions_containsMultipartMaxEntitySize() {
		assertThat( MiniServerConfig.DEFAULT_UNDERTOW_OPTIONS ).containsKey( "MULTIPART_MAX_ENTITY_SIZE" );
	}

	@Test
	void testDefaultUndertowOptions_maxEntitySizeIs25MB() {
		long expected = 25L * 1024L * 1024L; // 26_214_400
		assertThat( ( Long ) MiniServerConfig.DEFAULT_UNDERTOW_OPTIONS.get( "MAX_ENTITY_SIZE" ) ).isEqualTo( expected );
	}

	@Test
	void testDefaultUndertowOptions_multipartMaxEntitySizeIs100MB() {
		long expected = 100L * 1024L * 1024L; // 104_857_600
		assertThat( ( Long ) MiniServerConfig.DEFAULT_UNDERTOW_OPTIONS.get( "MULTIPART_MAX_ENTITY_SIZE" ) ).isEqualTo( expected );
	}

	@Test
	void testDefaultWorkerOptions_isEmpty() {
		assertThat( MiniServerConfig.DEFAULT_WORKER_OPTIONS ).isEmpty();
	}

	@Test
	void testDefaultSocketOptions_isEmpty() {
		assertThat( MiniServerConfig.DEFAULT_SOCKET_OPTIONS ).isEmpty();
	}

	@Test
	void testDefaultMaps_areUnmodifiable() {
		assertThrows( UnsupportedOperationException.class,
		    () -> MiniServerConfig.DEFAULT_UNDERTOW_OPTIONS.put( "SOME_KEY", 1 ) );
		assertThrows( UnsupportedOperationException.class,
		    () -> MiniServerConfig.DEFAULT_WORKER_OPTIONS.put( "SOME_KEY", 1 ) );
		assertThrows( UnsupportedOperationException.class,
		    () -> MiniServerConfig.DEFAULT_SOCKET_OPTIONS.put( "SOME_KEY", 1 ) );
	}

	// -------------------------------------------------------------------------
	// Instance defaults tests
	// -------------------------------------------------------------------------

	@Test
	void testNewInstance_undertowOptions_copiedFromDefaults() {
		MiniServerConfig config = new MiniServerConfig();
		assertThat( config.undertowOptions ).containsKey( "MAX_ENTITY_SIZE" );
		assertThat( config.undertowOptions ).containsKey( "MULTIPART_MAX_ENTITY_SIZE" );
	}

	@Test
	void testNewInstance_workerAndSocketOptions_empty() {
		MiniServerConfig config = new MiniServerConfig();
		assertThat( config.workerOptions ).isEmpty();
		assertThat( config.socketOptions ).isEmpty();
	}

	@Test
	void testNewInstance_optionMaps_areIndependentFromStaticDefaults() {
		MiniServerConfig config = new MiniServerConfig();
		// Mutating the instance map must not affect the static default
		config.undertowOptions.put( "CUSTOM_KEY", 42 );
		assertThat( MiniServerConfig.DEFAULT_UNDERTOW_OPTIONS ).doesNotContainKey( "CUSTOM_KEY" );
	}

	@Test
	void testNewInstance_defaults_port() {
		assertThat( new MiniServerConfig().port ).isEqualTo( MiniServerConfig.DEFAULT_PORT );
	}

	@Test
	void testNewInstance_defaults_host() {
		assertThat( new MiniServerConfig().host ).isEqualTo( MiniServerConfig.DEFAULT_HOST );
	}

	@Test
	void testNewInstance_defaults_rewriteFileName() {
		assertThat( new MiniServerConfig().rewriteFileName ).isEqualTo( MiniServerConfig.DEFAULT_REWRITE_FILE );
	}

	@Test
	void testNewInstance_warmupUrls_defaultsToEmptyList() {
		assertThat( new MiniServerConfig().warmupUrls ).isEmpty();
	}

	// -------------------------------------------------------------------------
	// validate() tests
	// -------------------------------------------------------------------------

	@Test
	void testValidate_validConfig_doesNotThrow() {
		MiniServerConfig config = new MiniServerConfig();
		config.validate(); // should not throw
	}

	@Test
	void testValidate_invalidPort_tooLow_throws() {
		MiniServerConfig config = new MiniServerConfig();
		config.port = 0;
		assertThrows( IllegalArgumentException.class, config::validate );
	}

	@Test
	void testValidate_invalidPort_tooHigh_throws() {
		MiniServerConfig config = new MiniServerConfig();
		config.port = 99999;
		assertThrows( IllegalArgumentException.class, config::validate );
	}

	@Test
	void testValidate_emptyHost_throws() {
		MiniServerConfig config = new MiniServerConfig();
		config.host = "";
		assertThrows( IllegalArgumentException.class, config::validate );
	}

	@Test
	void testValidate_emptyRewriteFileName_throws() {
		MiniServerConfig config = new MiniServerConfig();
		config.rewriteFileName = "";
		assertThrows( IllegalArgumentException.class, config::validate );
	}

	// -------------------------------------------------------------------------
	// applyJson() tests
	// -------------------------------------------------------------------------

	@Test
	void testApplyJson_basicFields_parsed() throws IOException {
		String				json	= "{ \"port\": 9090, \"host\": \"127.0.0.1\", \"debug\": true, \"rewrites\": true }";
		Path				tmp		= writeTempJson( json );

		MiniServerConfig	config	= new MiniServerConfig();
		config.applyJson( tmp.toString() );

		assertThat( config.port ).isEqualTo( 9090 );
		assertThat( config.host ).isEqualTo( "127.0.0.1" );
		assertThat( config.debug ).isTrue();
		assertThat( config.rewrites ).isTrue();
	}

	@Test
	void testApplyJson_undertowOptions_overridesDefault() throws IOException {
		long				newSize	= 50L * 1024L * 1024L; // 50 MB
		String				json	= "{ \"undertowOptions\": { \"MAX_ENTITY_SIZE\": " + newSize + " } }";
		Path				tmp		= writeTempJson( json );

		MiniServerConfig	config	= new MiniServerConfig();
		config.applyJson( tmp.toString() );

		// Jackson Jr may deserialize as Integer or Long depending on value range; compare as Number
		assertThat( ( ( Number ) config.undertowOptions.get( "MAX_ENTITY_SIZE" ) ).longValue() ).isEqualTo( newSize );
		// MULTIPART_MAX_ENTITY_SIZE should still be the default (not wiped)
		assertThat( config.undertowOptions ).containsKey( "MULTIPART_MAX_ENTITY_SIZE" );
	}

	@Test
	void testApplyJson_undertowOptions_addsNewKey() throws IOException {
		String				json	= "{ \"undertowOptions\": { \"IDLE_TIMEOUT\": 30000 } }";
		Path				tmp		= writeTempJson( json );

		MiniServerConfig	config	= new MiniServerConfig();
		config.applyJson( tmp.toString() );

		assertThat( config.undertowOptions ).containsKey( "IDLE_TIMEOUT" );
		assertThat( config.undertowOptions ).containsKey( "MAX_ENTITY_SIZE" ); // default preserved
	}

	@Test
	void testApplyJson_workerOptions_parsed() throws IOException {
		String				json	= "{ \"workerOptions\": { \"WORKER_TASK_MAX_THREADS\": 200 } }";
		Path				tmp		= writeTempJson( json );

		MiniServerConfig	config	= new MiniServerConfig();
		config.applyJson( tmp.toString() );

		assertThat( config.workerOptions ).containsKey( "WORKER_TASK_MAX_THREADS" );
	}

	@Test
	void testApplyJson_socketOptions_parsed() throws IOException {
		String				json	= "{ \"socketOptions\": { \"TCP_NODELAY\": true } }";
		Path				tmp		= writeTempJson( json );

		MiniServerConfig	config	= new MiniServerConfig();
		config.applyJson( tmp.toString() );

		assertThat( config.socketOptions ).containsKey( "TCP_NODELAY" );
	}

	@Test
	void testApplyJson_optionKeys_areUppercased() throws IOException {
		// miniserver.json should tolerate lowercase keys and normalize them
		String				json	= "{ \"undertowOptions\": { \"max_entity_size\": 1000 } }";
		Path				tmp		= writeTempJson( json );

		MiniServerConfig	config	= new MiniServerConfig();
		config.applyJson( tmp.toString() );

		assertThat( config.undertowOptions ).containsKey( "MAX_ENTITY_SIZE" );
	}

	@Test
	void testApplyJson_warmupUrls_array_parsed() throws IOException {
		String				json	= "{ \"warmupUrls\": [\"/init\", \"/health\"] }";
		Path				tmp		= writeTempJson( json );

		MiniServerConfig	config	= new MiniServerConfig();
		config.applyJson( tmp.toString() );

		assertThat( config.warmupUrls ).containsExactly( "/init", "/health" );
	}

	@Test
	void testApplyJson_warmupUrl_singleString_parsed() throws IOException {
		String				json	= "{ \"warmupUrl\": \"/init\" }";
		Path				tmp		= writeTempJson( json );

		MiniServerConfig	config	= new MiniServerConfig();
		config.applyJson( tmp.toString() );

		assertThat( config.warmupUrls ).containsExactly( "/init" );
	}

	@Test
	void testApplyJson_missingFile_throws() {
		MiniServerConfig config = new MiniServerConfig();
		assertThrows( IllegalArgumentException.class,
		    () -> config.applyJson( "/nonexistent/path/miniserver.json" ) );
	}

	// -------------------------------------------------------------------------
	// fromArgs() tests
	// -------------------------------------------------------------------------

	@Test
	void testFromArgs_noArgs_returnsDefaultConfig() {
		MiniServerConfig config = MiniServerConfig.fromArgs( new String[] {} );
		assertThat( config ).isNotNull();
		assertThat( config.port ).isEqualTo( MiniServerConfig.DEFAULT_PORT );
	}

	@Test
	void testFromArgs_port_parsedCorrectly() {
		MiniServerConfig config = MiniServerConfig.fromArgs( new String[] { "--port", "9999" } );
		assertThat( config.port ).isEqualTo( 9999 );
	}

	@Test
	void testFromArgs_webroot_parsedCorrectly() {
		String				path	= System.getProperty( "user.dir" );
		MiniServerConfig	config	= MiniServerConfig.fromArgs( new String[] { "--webroot", path } );
		assertThat( config.webRoot ).isEqualTo( path );
	}

	@Test
	void testFromArgs_debug_flag() {
		MiniServerConfig config = MiniServerConfig.fromArgs( new String[] { "--debug" } );
		assertThat( config.debug ).isTrue();
	}

	@Test
	void testFromArgs_rewrites_flag() {
		MiniServerConfig config = MiniServerConfig.fromArgs( new String[] { "--rewrites" } );
		assertThat( config.rewrites ).isTrue();
	}

	@Test
	void testFromArgs_unknownFlag_throws() {
		assertThrows( IllegalArgumentException.class,
		    () -> MiniServerConfig.fromArgs( new String[] { "--unknown-flag" } ) );
	}

	@Test
	void testFromArgs_invalidPort_throws() {
		assertThrows( IllegalArgumentException.class,
		    () -> MiniServerConfig.fromArgs( new String[] { "--port", "notanumber" } ) );
	}

	// -------------------------------------------------------------------------
	// aliases parsing tests
	// -------------------------------------------------------------------------

	@Test
	void testNewInstance_aliases_defaultsToEmptyMap() {
		assertThat( new MiniServerConfig().aliases ).isEmpty();
	}

	@Test
	void testApplyJson_aliases_structFormat_parsed() throws IOException {
		String				json	= "{ \"aliases\": { \"/docs\": \"/var/www/documentation\", \"/assets\": \"/srv/shared\" } }";
		Path				tmp		= writeTempJson( json );

		MiniServerConfig	config	= new MiniServerConfig();
		config.applyJson( tmp.toString() );

		assertThat( config.aliases ).containsKey( "/docs" );
		assertThat( config.aliases.get( "/docs" ) ).isEqualTo( "/var/www/documentation" );
		assertThat( config.aliases ).containsKey( "/assets" );
		assertThat( config.aliases.get( "/assets" ) ).isEqualTo( "/srv/shared" );
	}

	@Test
	void testApplyJson_aliases_arrayFormat_singleEntry_parsed() throws IOException {
		String				json	= "{ \"aliases\": [ { \"from\": \"/docs\", \"to\": \"/var/www/documentation\" } ] }";
		Path				tmp		= writeTempJson( json );

		MiniServerConfig	config	= new MiniServerConfig();
		config.applyJson( tmp.toString() );

		assertThat( config.aliases ).containsKey( "/docs" );
		assertThat( config.aliases.get( "/docs" ) ).isEqualTo( "/var/www/documentation" );
	}

	@Test
	void testApplyJson_aliases_arrayFormat_multipleEntries_parsed() throws IOException {
		String				json	= "{ \"aliases\": [ { \"from\": \"/docs\", \"to\": \"/var/www/docs\" },"
		    + " { \"from\": \"/api\", \"to\": \"/srv/api\" } ] }";
		Path				tmp		= writeTempJson( json );

		MiniServerConfig	config	= new MiniServerConfig();
		config.applyJson( tmp.toString() );

		assertThat( config.aliases ).hasSize( 2 );
		assertThat( config.aliases.get( "/docs" ) ).isEqualTo( "/var/www/docs" );
		assertThat( config.aliases.get( "/api" ) ).isEqualTo( "/srv/api" );
	}

	@Test
	void testApplyJson_aliases_invalidFormat_skipped() throws IOException {
		String				json	= "{ \"aliases\": \"not-valid\" }";
		Path				tmp		= writeTempJson( json );

		MiniServerConfig	config	= new MiniServerConfig();
		config.applyJson( tmp.toString() ); // must not throw

		assertThat( config.aliases ).isEmpty();
	}

	@Test
	void testApplyJson_aliases_arrayFormat_missingFromOrTo_skipped() throws IOException {
		String				json	= "{ \"aliases\": [ { \"from\": \"/docs\" }, { \"to\": \"/srv/x\" },"
		    + " { \"from\": \"/valid\", \"to\": \"/srv/valid\" } ] }";
		Path				tmp		= writeTempJson( json );

		MiniServerConfig	config	= new MiniServerConfig();
		config.applyJson( tmp.toString() );

		assertThat( config.aliases ).hasSize( 1 );
		assertThat( config.aliases.get( "/valid" ) ).isEqualTo( "/srv/valid" );
	}

	@Test
	void testApplyJson_aliases_absentKey_leavesMapEmpty() throws IOException {
		String				json	= "{ \"port\": 8080 }";
		Path				tmp		= writeTempJson( json );

		MiniServerConfig	config	= new MiniServerConfig();
		config.applyJson( tmp.toString() );

		assertThat( config.aliases ).isEmpty();
	}

	// -------------------------------------------------------------------------
	// Helpers
	// -------------------------------------------------------------------------

	private Path writeTempJson( String json ) throws IOException {
		Path tmp = Files.createTempFile( "miniserver-test-", ".json" );
		Files.writeString( tmp, json );
		tmp.toFile().deleteOnExit();
		return tmp;
	}

}
