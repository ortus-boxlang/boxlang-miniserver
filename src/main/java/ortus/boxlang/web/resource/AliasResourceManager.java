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

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import io.undertow.server.handlers.resource.PathResourceManager;
import io.undertow.server.handlers.resource.Resource;
import io.undertow.server.handlers.resource.ResourceChangeListener;
import io.undertow.server.handlers.resource.ResourceManager;

/**
 * A ResourceManager that delegates to per-alias PathResourceManagers for URL prefixes
 * matching configured aliases, falling back to a primary ResourceManager for the webroot.
 *
 * Alias prefixes are matched longest-first so that /docs/api takes precedence over /docs
 * when both are configured.
 */
public class AliasResourceManager implements ResourceManager {

	/** Single alias: URL prefix maps to a filesystem directory. */
	public static final class AliasEntry {

		public final String				urlPrefix;
		public final Path				targetRoot;
		private final PathResourceManager	manager;

		public AliasEntry( String urlPrefix, Path targetRoot ) {
			this.urlPrefix	= normalizePrefix( urlPrefix );
			this.targetRoot	= targetRoot;
			this.manager	= new PathResourceManager( targetRoot, 1024, true, true );
		}

		Resource getResource( String relativePath ) throws IOException {
			return manager.getResource( relativePath );
		}

		void close() throws IOException {
			manager.close();
		}
	}

	private final ResourceManager	primary;
	private final List<AliasEntry>	aliases;

	/**
	 * @param primary   ResourceManager for the webroot (fallback)
	 * @param aliasMap  URL prefix to absolute filesystem Path, in any order
	 */
	public AliasResourceManager( ResourceManager primary, Map<String, Path> aliasMap ) {
		this.primary = primary;
		List<AliasEntry> list = new ArrayList<>();
		for ( Map.Entry<String, Path> entry : aliasMap.entrySet() ) {
			list.add( new AliasEntry( entry.getKey(), entry.getValue() ) );
		}
		// Longest prefix first for correct longest-match semantics
		list.sort( Comparator.comparingInt( ( AliasEntry e ) -> e.urlPrefix.length() ).reversed() );
		this.aliases = list;
	}

	@Override
	public Resource getResource( String path ) throws IOException {
		if ( path == null ) {
			return null;
		}
		String normalized = normalizePath( path );

		for ( AliasEntry alias : aliases ) {
			if ( matchesPrefix( normalized, alias.urlPrefix ) ) {
				String aliasRelative = normalized.substring( alias.urlPrefix.length() );
				if ( aliasRelative.isEmpty() ) {
					aliasRelative = "/";
				}
				Resource resource = alias.getResource( aliasRelative );
				if ( resource != null ) {
					return resource;
				}
			}
		}
		return primary.getResource( normalized );
	}

	/**
	 * Returns the alias filesystem root Path if the URL matches a configured alias prefix,
	 * or null if no alias matches. Used by BLHandler to determine the effective webRoot.
	 *
	 * @param urlPath The URL-relative path (e.g. "/docs/guide.bxm")
	 *
	 * @return The alias target root Path, or null
	 */
	public Path resolveAliasRoot( String urlPath ) {
		if ( urlPath == null ) {
			return null;
		}
		String normalized = normalizePath( urlPath );
		for ( AliasEntry alias : aliases ) {
			if ( matchesPrefix( normalized, alias.urlPrefix ) ) {
				return alias.targetRoot;
			}
		}
		return null;
	}

	/**
	 * Strips the alias URL prefix from a matching path and returns the remainder.
	 * Returns the original path if no alias matches.
	 *
	 * @param urlPath The URL-relative path (e.g. "/docs/guide.bxm")
	 *
	 * @return The alias-relative path (e.g. "/guide.bxm"), or the original path
	 */
	public String resolveAliasRelativePath( String urlPath ) {
		if ( urlPath == null ) {
			return null;
		}
		String normalized = normalizePath( urlPath );
		for ( AliasEntry alias : aliases ) {
			if ( matchesPrefix( normalized, alias.urlPrefix ) ) {
				String rel = normalized.substring( alias.urlPrefix.length() );
				return rel.isEmpty() ? "/" : rel;
			}
		}
		return urlPath;
	}

	@Override
	public boolean isResourceChangeListenerSupported() {
		return false;
	}

	@Override
	public void registerResourceChangeListener( ResourceChangeListener listener ) {
		// not supported
	}

	@Override
	public void removeResourceChangeListener( ResourceChangeListener listener ) {
		// not supported
	}

	@Override
	public void close() throws IOException {
		for ( AliasEntry alias : aliases ) {
			alias.close();
		}
		primary.close();
	}

	// -------------------------------------------------------------------------
	// Helpers
	// -------------------------------------------------------------------------

	/**
	 * Ensures leading slash and removes trailing slash (except for root "/").
	 */
	private static String normalizePath( String path ) {
		if ( path == null || path.isEmpty() ) {
			return "/";
		}
		if ( !path.startsWith( "/" ) ) {
			path = "/" + path;
		}
		if ( path.length() > 1 && path.endsWith( "/" ) ) {
			path = path.substring( 0, path.length() - 1 );
		}
		return path;
	}

	/**
	 * Normalizes an alias URL prefix: adds leading slash, strips trailing slash.
	 */
	static String normalizePrefix( String prefix ) {
		if ( prefix == null || prefix.isEmpty() ) {
			return "/";
		}
		if ( !prefix.startsWith( "/" ) ) {
			prefix = "/" + prefix;
		}
		if ( prefix.length() > 1 && prefix.endsWith( "/" ) ) {
			prefix = prefix.substring( 0, prefix.length() - 1 );
		}
		return prefix;
	}

	/**
	 * True if {@code path} starts with {@code prefix} and the match is at a path-segment
	 * boundary — preventing "/documentation" from matching the alias "/docs".
	 */
	static boolean matchesPrefix( String path, String prefix ) {
		if ( prefix == null || prefix.equals( "/" ) ) {
			return true;
		}
		if ( !path.startsWith( prefix ) ) {
			return false;
		}
		if ( path.length() == prefix.length() ) {
			return true;
		}
		return path.charAt( prefix.length() ) == '/';
	}

}
