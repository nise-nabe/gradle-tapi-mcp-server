package com.example.gradle.mcp.protocol

import io.modelcontextprotocol.kotlin.sdk.types.ResourceTemplate
import io.modelcontextprotocol.kotlin.sdk.utils.MatchResult
import io.modelcontextprotocol.kotlin.sdk.utils.PathSegmentTemplateMatcher
import io.modelcontextprotocol.kotlin.sdk.utils.ResourceTemplateMatcher
import io.modelcontextprotocol.kotlin.sdk.utils.ResourceTemplateMatcherFactory

/**
 * Kotlin MCP SDK 0.15.0 [PathSegmentTemplateMatcher] treats `?query` as part of the
 * last path segment, so `.../overview?projectPath=%3Aplugin` would not match
 * `.../overview`. Strip query and fragment before matching; handlers parse them
 * from the original [io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequest.uri].
 */
internal class QueryStrippingPathSegmentMatcher(
    override val resourceTemplate: ResourceTemplate,
) : ResourceTemplateMatcher {
    private val delegate = PathSegmentTemplateMatcher(resourceTemplate)

    override fun match(resourceUri: String): MatchResult? =
        delegate.match(canonicalizeGradleTapiScheme(stripQueryAndFragment(resourceUri)))

    companion object {
        val factory: ResourceTemplateMatcherFactory = ResourceTemplateMatcherFactory { template ->
            QueryStrippingPathSegmentMatcher(template)
        }

        fun stripQueryAndFragment(uri: String): String {
            val withoutFragment = uri.substringBefore('#')
            return withoutFragment.substringBefore('?')
        }

        /**
         * RFC 3986 schemes are case-insensitive. The SDK matcher compares the
         * advertised lowercase `gradle-tapi://` template literally.
         */
        fun canonicalizeGradleTapiScheme(uri: String): String {
            val schemeSeparator = uri.indexOf("://")
            if (schemeSeparator <= 0) {
                return uri
            }
            val scheme = uri.substring(0, schemeSeparator)
            if (!scheme.equals(GradleTapiResourceUri.SCHEME, ignoreCase = true)) {
                return uri
            }
            if (scheme == GradleTapiResourceUri.SCHEME) {
                return uri
            }
            return GradleTapiResourceUri.SCHEME + uri.substring(schemeSeparator)
        }
    }
}
