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
package ortus.boxlang.web.resource;

import static com.google.common.truth.Truth.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.undertow.server.handlers.resource.PathResourceManager;
import io.undertow.server.handlers.resource.Resource;

/**
 * Tests for AliasResourceManager — alias resolution, fallback, and boundary matching.
 * Uses real temporary directories; no BoxLang runtime needed.
 */
public class AliasResourceManagerTest {

	private Path				webrootDir;
	private Path				docsAliasDir;
	private Path				apiAliasDir;
	private PathResourceManager	primaryManager;

	@BeforeEach
	void setUp() throws IOException {
		webrootDir		= Files.createTempDirectory( "arm-webroot-" );
		docsAliasDir	= Files.createTempDirectory( "arm-docs-" );
		apiAliasDir		= Files.createTempDirectory( "arm-api-" );

		// Create test files
		Files.writeString( webrootDir.resolve( "index.html" ), "<html>webroot</html>" );
		Files.writeString( docsAliasDir.resolve( "guide.html" ), "<html>guide</html>" );
		Files.writeString( apiAliasDir.resolve( "swagger.json" ), "{}" );

		primaryManager = new PathResourceManager( webrootDir, 1024, true, true );
	}

	@AfterEach
	void tearDown() throws IOException {
		primaryManager.close();
	}

	private AliasResourceManager buildManager( Map<String, Path> aliases ) {
		return new AliasResourceManager( primaryManager, aliases );
	}

	// -------------------------------------------------------------------------
	// getResource — basic resolution
	// -------------------------------------------------------------------------

	@Test
	void getResource_webrootFile_resolvedFromPrimary() throws IOException {
		AliasResourceManager arm = buildManager( Map.of( "/docs", docsAliasDir ) );

		Resource r = arm.getResource( "/index.html" );
		assertThat( r ).isNotNull();
	}

	@Test
	void getResource_aliasedFile_resolvedFromAliasDir() throws IOException {
		AliasResourceManager arm = buildManager( Map.of( "/docs", docsAliasDir ) );

		Resource r = arm.getResource( "/docs/guide.html" );
		assertThat( r ).isNotNull();
	}

	@Test
	void getResource_nonexistentFile_returnsNull() throws IOException {
		AliasResourceManager arm = buildManager( Map.of( "/docs", docsAliasDir ) );

		Resource r = arm.getResource( "/docs/missing.html" );
		assertThat( r ).isNull();
	}

	@Test
	void getResource_noAlias_unknownPath_returnsNull() throws IOException {
		AliasResourceManager arm = buildManager( Map.of( "/docs", docsAliasDir ) );

		Resource r = arm.getResource( "/other/file.html" );
		assertThat( r ).isNull();
	}

	// -------------------------------------------------------------------------
	// Boundary check: /docs must NOT match /documentation
	// -------------------------------------------------------------------------

	@Test
	void getResource_boundaryCheck_documentationDoesNotMatchDocsAlias() throws IOException {
		// Create a file in primary webroot under /documentation/
		Path docDir = webrootDir.resolve( "documentation" );
		Files.createDirectory( docDir );
		Files.writeString( docDir.resolve( "readme.html" ), "<html>readme</html>" );

		AliasResourceManager arm = buildManager( Map.of( "/docs", docsAliasDir ) );

		// /documentation/readme.html should come from the webroot, not the docs alias
		Resource r = arm.getResource( "/documentation/readme.html" );
		assertThat( r ).isNotNull();
		// The resource path should be under webrootDir, not docsAliasDir
		String resourceCanonical = r.getFile().getCanonicalPath();
		assertThat( resourceCanonical ).startsWith( webrootDir.toFile().getCanonicalPath() );
	}

	// -------------------------------------------------------------------------
	// Longest-match wins
	// -------------------------------------------------------------------------

	@Test
	void getResource_longestPrefixWins() throws IOException {
		Path deepAliasDir = Files.createTempDirectory( "arm-deep-" );
		Files.writeString( deepAliasDir.resolve( "spec.json" ), "{\"deep\":true}" );

		Map<String, Path> aliases = new LinkedHashMap<>();
		aliases.put( "/api", apiAliasDir );
		aliases.put( "/api/v2", deepAliasDir );
		AliasResourceManager arm = buildManager( aliases );

		// /api/v2/spec.json should resolve from deepAliasDir, not apiAliasDir
		Resource r = arm.getResource( "/api/v2/spec.json" );
		assertThat( r ).isNotNull();
		assertThat( r.getFile().getCanonicalPath() ).startsWith( deepAliasDir.toFile().getCanonicalPath() );

		deepAliasDir.toFile().deleteOnExit();
	}

	// -------------------------------------------------------------------------
	// resolveAliasRoot
	// -------------------------------------------------------------------------

	@Test
	void resolveAliasRoot_matchingPath_returnsAliasRoot() {
		AliasResourceManager arm = buildManager( Map.of( "/docs", docsAliasDir ) );

		Path root = arm.resolveAliasRoot( "/docs/guide.html" );
		assertThat( root ).isEqualTo( docsAliasDir );
	}

	@Test
	void resolveAliasRoot_nonMatchingPath_returnsNull() {
		AliasResourceManager arm = buildManager( Map.of( "/docs", docsAliasDir ) );

		Path root = arm.resolveAliasRoot( "/other/file.html" );
		assertThat( root ).isNull();
	}

	@Test
	void resolveAliasRoot_boundaryCheck_doesNotMatchShorterAlias() {
		AliasResourceManager arm = buildManager( Map.of( "/docs", docsAliasDir ) );

		Path root = arm.resolveAliasRoot( "/documentation/index.html" );
		assertThat( root ).isNull();
	}

	// -------------------------------------------------------------------------
	// resolveAliasRelativePath
	// -------------------------------------------------------------------------

	@Test
	void resolveAliasRelativePath_stripsPrefix() {
		AliasResourceManager arm = buildManager( Map.of( "/docs", docsAliasDir ) );

		String rel = arm.resolveAliasRelativePath( "/docs/guide.html" );
		assertThat( rel ).isEqualTo( "/guide.html" );
	}

	@Test
	void resolveAliasRelativePath_exactPrefix_returnsSlash() {
		AliasResourceManager arm = buildManager( Map.of( "/docs", docsAliasDir ) );

		String rel = arm.resolveAliasRelativePath( "/docs" );
		assertThat( rel ).isEqualTo( "/" );
	}

	@Test
	void resolveAliasRelativePath_noMatch_returnsOriginal() {
		AliasResourceManager arm = buildManager( Map.of( "/docs", docsAliasDir ) );

		String rel = arm.resolveAliasRelativePath( "/other/file.html" );
		assertThat( rel ).isEqualTo( "/other/file.html" );
	}

	// -------------------------------------------------------------------------
	// normalizePrefix / matchesPrefix static helpers
	// -------------------------------------------------------------------------

	@Test
	void normalizePrefix_stripsTrailingSlash() {
		assertThat( AliasResourceManager.normalizePrefix( "/docs/" ) ).isEqualTo( "/docs" );
	}

	@Test
	void normalizePrefix_addsLeadingSlash() {
		assertThat( AliasResourceManager.normalizePrefix( "docs" ) ).isEqualTo( "/docs" );
	}

	@Test
	void matchesPrefix_exactMatch_returnsTrue() {
		assertThat( AliasResourceManager.matchesPrefix( "/docs", "/docs" ) ).isTrue();
	}

	@Test
	void matchesPrefix_subPath_returnsTrue() {
		assertThat( AliasResourceManager.matchesPrefix( "/docs/guide.html", "/docs" ) ).isTrue();
	}

	@Test
	void matchesPrefix_partialSegment_returnsFalse() {
		assertThat( AliasResourceManager.matchesPrefix( "/documentation", "/docs" ) ).isFalse();
	}

	@Test
	void matchesPrefix_rootPrefix_alwaysTrue() {
		assertThat( AliasResourceManager.matchesPrefix( "/anything", "/" ) ).isTrue();
	}

	// -------------------------------------------------------------------------
	// close
	// -------------------------------------------------------------------------

	@Test
	void close_doesNotThrow() throws IOException {
		AliasResourceManager arm = buildManager( Map.of( "/docs", docsAliasDir ) );
		arm.close(); // should not throw — primaryManager is closed here too, reset field
		primaryManager = new PathResourceManager( webrootDir, 1024, true, true );
	}

}
