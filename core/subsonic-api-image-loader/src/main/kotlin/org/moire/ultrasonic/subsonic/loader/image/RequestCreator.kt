package org.moire.ultrasonic.subsonic.loader.image

import android.net.Uri

internal const val SCHEME = "subsonic_api"
internal const val AUTHORITY = BuildConfig.LIBRARY_PACKAGE_NAME
internal const val COVER_ART_PATH = "cover_art"
internal const val AVATAR_PATH = "avatar"
internal const val QUERY_ID = "id"
internal const val SIZE = "size"
internal const val QUERY_USERNAME = "username"

/**
 * Picasso.load() only accepts an URI as parameter. Therefore we create a bogus URI, in which
 * we encode the data that we need in the RequestHandler.
 */
internal fun createLoadCoverArtRequest(config: ImageRequest.CoverArt): Uri =
    Uri.Builder()
    .scheme(SCHEME)
    .authority(AUTHORITY)
    .appendPath(COVER_ART_PATH)
    .appendQueryParameter(QUERY_ID, config.entityId)
    .appendQueryParameter(SIZE, config.size.toString())
    .build()

internal fun createLoadAvatarRequest(username: String): Uri =
    Uri.Builder()
    .scheme(SCHEME)
    .authority(AUTHORITY)
    .appendPath(AVATAR_PATH)
    .appendQueryParameter(QUERY_USERNAME, username)
    .build()
