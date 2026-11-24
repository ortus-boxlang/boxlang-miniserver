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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression test for the generated Unix launcher: BOXLANG_HOME, the webroot and the install
 * directory all contain spaces, and the server must still start and answer requests.
 *
 * The launcher script and the shaded jar are produced by Gradle, which passes their locations in
 * as the launcher.script and launcher.jar system properties. The test is skipped when they are absent.
 */
public class LauncherSpacedPathTest {

	@TempDir
	Path tempDir;

	@Test
	void testLauncherStartsWhenHomeWebrootAndInstallPathsContainSpaces() throws Exception {
		String	scriptProp	= System.getProperty( "launcher.script" );
		String	jarProp		= System.getProperty( "launcher.jar" );
		assumeTrue( !System.getProperty( "os.name" ).toLowerCase().contains( "win" ), "Unix launcher only" );
		assumeTrue( scriptProp != null && jarProp != null, "launcher.script / launcher.jar not provided" );
		Path	script	= Path.of( scriptProp );
		Path	jar		= Path.of( jarProp );
		assumeTrue( Files.isRegularFile( script ) && Files.isRegularFile( jar ), "launcher artifacts not built" );

		// Lay out a distribution (bin/ + lib/) under a directory with a space in its name
		Path	dist		= Files.createDirectories( this.tempDir.resolve( "install dir" ) );
		Path	bin			= Files.createDirectories( dist.resolve( "bin" ) );
		Path	lib			= Files.createDirectories( dist.resolve( "lib" ) );
		Path	launcher	= Files.copy( script, bin.resolve( "boxlang-miniserver" ) );
		Files.setPosixFilePermissions( launcher, PosixFilePermissions.fromString( "rwxr-xr-x" ) );
		Files.copy( jar, lib.resolve( jar.getFileName() ) );

		Path	home	= Files.createDirectories( this.tempDir.resolve( "box home" ) );
		Path	webroot	= Files.createDirectories( this.tempDir.resolve( "web root" ) );
		Files.writeString( webroot.resolve( "hello.txt" ), "hello" );
		int				port	= freePort();

		ProcessBuilder	builder	= new ProcessBuilder(
		    launcher.toString(), "-p", String.valueOf( port ), "-w", webroot.toString(), "--health-check" )
		    .redirectErrorStream( true )
		    .redirectOutput( this.tempDir.resolve( "server.log" ).toFile() );
		builder.environment().put( "BOXLANG_HOME", home.toString() );
		Process process = builder.start();

		try {
			HttpClient	client		= HttpClient.newHttpClient();
			int			health		= -1;
			long		deadline	= System.currentTimeMillis() + Duration.ofSeconds( 120 ).toMillis();
			while ( System.currentTimeMillis() < deadline && process.isAlive() && health != 200 ) {
				health = status( client, port, "/health" );
				if ( health != 200 ) {
					Thread.sleep( 500 );
				}
			}
			String log = Files.readString( this.tempDir.resolve( "server.log" ) );
			assertThat( log ).doesNotContain( "Could not find or load main class" );
			assertThat( health ).isEqualTo( 200 );

			HttpResponse<String> hello = client.send(
			    HttpRequest.newBuilder( URI.create( "http://127.0.0.1:" + port + "/hello.txt" ) ).build(),
			    HttpResponse.BodyHandlers.ofString() );
			assertThat( hello.statusCode() ).isEqualTo( 200 );
			assertThat( hello.body().trim() ).isEqualTo( "hello" );
		} finally {
			process.descendants().forEach( ProcessHandle::destroyForcibly );
			process.destroyForcibly();
		}
	}

	private static int status( HttpClient client, int port, String path ) throws InterruptedException {
		try {
			return client.send(
			    HttpRequest.newBuilder( URI.create( "http://127.0.0.1:" + port + path ) ).build(),
			    HttpResponse.BodyHandlers.discarding() ).statusCode();
		} catch ( IOException e ) {
			return -1;
		}
	}

	private static int freePort() throws IOException {
		try ( ServerSocket socket = new ServerSocket( 0 ) ) {
			return socket.getLocalPort();
		}
	}
}
