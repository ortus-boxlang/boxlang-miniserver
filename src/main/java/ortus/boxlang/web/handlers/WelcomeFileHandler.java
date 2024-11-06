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
import java.util.List;

import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.resource.Resource;
import io.undertow.server.handlers.resource.ResourceManager;
import io.undertow.util.CanonicalPathUtils;
import io.undertow.util.RedirectBuilder;
import io.undertow.util.StatusCodes;

/**
 * The WelcomeFileHandler is an Undertow HttpHandler that serves welcome files.
 */
public class WelcomeFileHandler implements HttpHandler {

	private final HttpHandler		next;
	private final ResourceManager	resourceManager;
	private List<String>			welcomeFiles;

	/**
	 * Create a new WelcomeFileHandler
	 *
	 * @param next            The next HttpHandler
	 * @param resourceManager The ResourceManager
	 * @param welcomeFiles    The list of welcome files
	 */
	public WelcomeFileHandler( final HttpHandler next, ResourceManager resourceManager, List<String> welcomeFiles ) {
		this.next				= next;
		this.resourceManager	= resourceManager;
		this.welcomeFiles		= welcomeFiles;
	}

	/**
	 * Handle the request
	 *
	 * @param exchange The HttpServerExchange
	 *
	 * @throws Exception
	 */
	@Override
	public void handleRequest( final HttpServerExchange exchange ) throws Exception {
		Resource resource = resourceManager.getResource( canonicalize( exchange.getRelativePath() ) );
		if ( resource != null && resource.isDirectory() ) {
			// First ensure that the directory has a trailing slash, and if not, redirect it
			if ( !exchange.getRequestPath().endsWith( "/" ) ) {
				exchange.setStatusCode( StatusCodes.FOUND );
				exchange.getResponseHeaders().put( io.undertow.util.Headers.LOCATION,
				    RedirectBuilder.redirect( exchange, exchange.getRelativePath() + "/", true ) );
				exchange.endExchange();
				return;
			}
			// if it's a directory and we have the trailing slash, then let's look for welcome files
			Resource indexResource = getIndexFiles( exchange, resourceManager, resource.getPath(), welcomeFiles );
			if ( indexResource != null ) {
				String newPath = indexResource.getPath();
				// ensure leading slash
				if ( !newPath.startsWith( "/" ) ) {
					newPath = "/" + newPath;
				}
				exchange.setRelativePath( newPath );
			}
		}

		next.handleRequest( exchange );
	}

	/**
	 * Get the index files
	 *
	 * @param exchange        The HttpServerExchange
	 * @param resourceManager The ResourceManager
	 * @param base            The base path
	 * @param possible        The list of possible index files
	 *
	 * @return
	 *
	 * @throws IOException
	 */
	private Resource getIndexFiles( HttpServerExchange exchange, ResourceManager resourceManager, final String base,
	    List<String> possible ) throws IOException {
		if ( possible == null ) {
			return null;
		}
		String realBase;
		if ( base.endsWith( "/" ) ) {
			realBase = base;
		} else {
			realBase = base + "/";
		}
		for ( String possibility : possible ) {
			Resource index = resourceManager.getResource( canonicalize( realBase + possibility ) );
			if ( index != null ) {
				return index;
			}
		}
		return null;
	}

	/**
	 * Canonicalize the path
	 *
	 * @param s The path
	 *
	 * @return The canonicalized path
	 */
	private String canonicalize( String s ) {
		return CanonicalPathUtils.canonicalize( s );
	}
}
