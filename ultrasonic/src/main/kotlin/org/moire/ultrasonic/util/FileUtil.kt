/*
 * FileUtil.kt
 * Copyright (C) 2009-2021 Ultrasonic developers
 *
 * Distributed under terms of the GNU GPLv3 license.
 */

package org.moire.ultrasonic.util

import android.content.Context
import android.os.Build
import android.os.Environment
import android.text.TextUtils
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FileWriter
import java.io.IOException
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.io.Serializable
import java.util.Locale
import java.util.SortedSet
import java.util.TreeSet
import java.util.regex.Pattern
import org.koin.java.KoinJavaComponent
import org.moire.ultrasonic.app.UApp
import org.moire.ultrasonic.domain.MusicDirectory
import timber.log.Timber

object FileUtil {

    private val FILE_SYSTEM_UNSAFE = arrayOf("/", "\\", "..", ":", "\"", "?", "*", "<", ">", "|")
    private val FILE_SYSTEM_UNSAFE_DIR = arrayOf("\\", "..", ":", "\"", "?", "*", "<", ">", "|")
    private val MUSIC_FILE_EXTENSIONS =
        listOf("mp3", "ogg", "aac", "flac", "m4a", "wav", "wma", "opus")
    private val VIDEO_FILE_EXTENSIONS =
        listOf("flv", "mp4", "m4v", "wmv", "avi", "mov", "mpg", "mkv")
    private val PLAYLIST_FILE_EXTENSIONS = listOf("m3u")
    private val TITLE_WITH_TRACK = Pattern.compile("^\\d\\d-.*")
    const val SUFFIX_LARGE = ".jpeg"
    const val SUFFIX_SMALL = ".jpeg-small"
    private const val UNNAMED = "unnamed"

    private val permissionUtil = KoinJavaComponent.inject<PermissionUtil>(
        PermissionUtil::class.java
    )

    fun getSongFile(song: MusicDirectory.Entry): File {
        val dir = getAlbumDirectory(song)

        // Do not generate new name for offline files. Offline files will have their Path as their Id.
        if (!TextUtils.isEmpty(song.id)) {
            if (song.id.startsWith(dir.absolutePath)) return File(song.id)
        }

        // Generate a file name for the song
        val fileName = StringBuilder(256)
        val track = song.track

        // check if filename already had track number
        if (song.title != null && !TITLE_WITH_TRACK.matcher(song.title!!).matches()) {
            if (track != null) {
                if (track < 10) {
                    fileName.append('0')
                }
                fileName.append(track).append('-')
            }
        }
        fileName.append(fileSystemSafe(song.title)).append('.')
        if (!TextUtils.isEmpty(song.transcodedSuffix)) {
            fileName.append(song.transcodedSuffix)
        } else {
            fileName.append(song.suffix)
        }
        return File(dir, fileName.toString())
    }

    @JvmStatic
    fun getPlaylistFile(server: String?, name: String?): File {
        val playlistDir = getPlaylistDirectory(server)
        return File(playlistDir, String.format(Locale.ROOT, "%s.m3u", fileSystemSafe(name)))
    }

    @JvmStatic
    val playlistDirectory: File
        get() {
            val playlistDir = File(ultrasonicDirectory, "playlists")
            ensureDirectoryExistsAndIsReadWritable(playlistDir)
            return playlistDir
        }

    @JvmStatic
    fun getPlaylistDirectory(server: String? = null): File {
        val playlistDir: File
        if (server != null) {
            playlistDir = File(playlistDirectory, server)
        } else {
            playlistDir = playlistDirectory
        }
        ensureDirectoryExistsAndIsReadWritable(playlistDir)
        return playlistDir
    }

    /**
     * Get the album art file for a given album entry
     * @param entry The album entry
     * @return File object. Not guaranteed that it exists
     */
    fun getAlbumArtFile(entry: MusicDirectory.Entry): File {
        val albumDir = getAlbumDirectory(entry)
        return getAlbumArtFile(albumDir)
    }

    /**
     * Get the cache key for a given album entry
     * @param entry The album entry
     * @param large Whether to get the key for the large or the default image
     * @return String The hash key
     */
    fun getAlbumArtKey(entry: MusicDirectory.Entry?, large: Boolean): String? {
        if (entry == null) return null
        val albumDir = getAlbumDirectory(entry)
        return getAlbumArtKey(albumDir, large)
    }

    /**
     * Get the cache key for a given artist
     * @param name The artist name
     * @param large Whether to get the key for the large or the default image
     * @return String The hash key
     */
    fun getArtistArtKey(name: String?, large: Boolean): String {
        val artist = fileSystemSafe(name)
        val dir = File(String.format(Locale.ROOT, "%s/%s/%s", musicDirectory.path, artist, UNNAMED))
        return getAlbumArtKey(dir, large)
    }

    /**
     * Get the cache key for a given album entry
     * @param albumDir The album directory
     * @param large Whether to get the key for the large or the default image
     * @return String The hash key
     */
    private fun getAlbumArtKey(albumDir: File, large: Boolean): String {
        val suffix = if (large) SUFFIX_LARGE else SUFFIX_SMALL
        return String.format(Locale.ROOT, "%s%s", Util.md5Hex(albumDir.path), suffix)
    }

    fun getAvatarFile(username: String?): File? {
        if (username == null) {
            return null
        }
        val albumArtDir = albumArtDirectory
        val md5Hex = Util.md5Hex(username)
        return File(albumArtDir, String.format(Locale.ROOT, "%s%s", md5Hex, SUFFIX_LARGE))
    }

    /**
     * Get the album art file for a given album directory
     * @param albumDir The album directory
     * @return File object. Not guaranteed that it exists
     */
    @JvmStatic
    fun getAlbumArtFile(albumDir: File): File {
        val albumArtDir = albumArtDirectory
        val key = getAlbumArtKey(albumDir, true)
        return File(albumArtDir, key)
    }

    /**
     * Get the album art file for a given cache key
     * @param cacheKey The key (== the filename)
     * @return File object. Not guaranteed that it exists
     */
    @JvmStatic
    fun getAlbumArtFile(cacheKey: String?): File? {
        val albumArtDir = albumArtDirectory
        return if (cacheKey == null) {
            null
        } else File(albumArtDir, cacheKey)
    }

    val albumArtDirectory: File
        get() {
            val albumArtDir = File(ultrasonicDirectory, "artwork")
            ensureDirectoryExistsAndIsReadWritable(albumArtDir)
            ensureDirectoryExistsAndIsReadWritable(File(albumArtDir, ".nomedia"))
            return albumArtDir
        }

    fun getAlbumDirectory(entry: MusicDirectory.Entry): File {
        val dir: File
        if (!TextUtils.isEmpty(entry.path)) {
            val f = File(fileSystemSafeDir(entry.path))
            dir = File(
                String.format(
                    Locale.ROOT,
                    "%s/%s",
                    musicDirectory.path,
                    if (entry.isDirectory) f.path else f.parent ?: ""
                )
            )
        } else {
            val artist = fileSystemSafe(entry.artist)
            var album = fileSystemSafe(entry.album)
            if (UNNAMED == album) {
                album = fileSystemSafe(entry.title)
            }
            dir = File(String.format(Locale.ROOT, "%s/%s/%s", musicDirectory.path, artist, album))
        }
        return dir
    }

    fun createDirectoryForParent(file: File) {
        val dir = file.parentFile
        if (dir != null && !dir.exists()) {
            if (!dir.mkdirs()) {
                Timber.e("Failed to create directory %s", dir)
            }
        }
    }

    @Suppress("SameParameterValue")
    private fun getOrCreateDirectory(name: String): File {
        val dir = File(ultrasonicDirectory, name)
        if (!dir.exists() && !dir.mkdirs()) {
            Timber.e("Failed to create %s", name)
        }
        return dir
    }

    // After Android M, the location of the files must be queried differently.
    // GetExternalFilesDir will always return a directory which Ultrasonic
    // can access without any extra privileges.
    @JvmStatic
    val ultrasonicDirectory: File
        get() {
            return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) File(
                Environment.getExternalStorageDirectory(),
                "Android/data/org.moire.ultrasonic"
            ) else UApp.applicationContext().getExternalFilesDir(null)!!
        }

    // After Android M, the location of the files must be queried differently.
    // GetExternalFilesDir will always return a directory which Ultrasonic
    // can access without any extra privileges.
    @JvmStatic
    val defaultMusicDirectory: File
        get() = getOrCreateDirectory("music")

    @JvmStatic
    val musicDirectory: File
        get() {
            val path = Settings.preferences
                .getString(Constants.PREFERENCES_KEY_CACHE_LOCATION, defaultMusicDirectory.path)
            val dir = File(path!!)
            val hasAccess = ensureDirectoryExistsAndIsReadWritable(dir)
            if (!hasAccess) permissionUtil.value.handlePermissionFailed(null)
            return if (hasAccess) dir else defaultMusicDirectory
        }

    @JvmStatic
    @Suppress("ReturnCount")
    fun ensureDirectoryExistsAndIsReadWritable(dir: File?): Boolean {
        if (dir == null) {
            return false
        }
        if (dir.exists()) {
            if (!dir.isDirectory) {
                Timber.w("%s exists but is not a directory.", dir)
                return false
            }
        } else {
            if (dir.mkdirs()) {
                Timber.i("Created directory %s", dir)
            } else {
                Timber.w("Failed to create directory %s", dir)
                return false
            }
        }
        if (!dir.canRead()) {
            Timber.w("No read permission for directory %s", dir)
            return false
        }
        if (!dir.canWrite()) {
            Timber.w("No write permission for directory %s", dir)
            return false
        }
        return true
    }

    /**
     * Makes a given filename safe by replacing special characters like slashes ("/" and "\")
     * with dashes ("-").
     *
     * @param name The filename in question.
     * @return The filename with special characters replaced by hyphens.
     */
    private fun fileSystemSafe(name: String?): String {
        if (name == null || name.trim { it <= ' ' }.isEmpty()) {
            return UNNAMED
        }
        var filename: String = name

        for (s in FILE_SYSTEM_UNSAFE) {
            filename = filename.replace(s, "-")
        }
        return filename
    }

    /**
     * Makes a given filename safe by replacing special characters like colons (":")
     * with dashes ("-").
     *
     * @param path The path of the directory in question.
     * @return The the directory name with special characters replaced by hyphens.
     */
    private fun fileSystemSafeDir(path: String?): String {
        var filepath = path
        if (filepath == null || filepath.trim { it <= ' ' }.isEmpty()) {
            return ""
        }
        for (s in FILE_SYSTEM_UNSAFE_DIR) {
            filepath = filepath!!.replace(s, "-")
        }
        return filepath!!
    }

    /**
     * Similar to [File.listFiles], but returns a sorted set.
     * Never returns `null`, instead a warning is logged, and an empty set is returned.
     */
    @JvmStatic
    fun listFiles(dir: File): SortedSet<File> {
        val files = dir.listFiles()
        if (files == null) {
            Timber.w("Failed to list children for %s", dir.path)
            return TreeSet()
        }
        return TreeSet(files.asList())
    }

    fun listMediaFiles(dir: File): SortedSet<File> {
        val files = listFiles(dir)
        val iterator = files.iterator()
        while (iterator.hasNext()) {
            val file = iterator.next()
            if (!file.isDirectory && !isMediaFile(file)) {
                iterator.remove()
            }
        }
        return files
    }

    private fun isMediaFile(file: File): Boolean {
        val extension = getExtension(file.name)
        return MUSIC_FILE_EXTENSIONS.contains(extension) ||
            VIDEO_FILE_EXTENSIONS.contains(extension)
    }

    fun isPlaylistFile(file: File): Boolean {
        val extension = getExtension(file.name)
        return PLAYLIST_FILE_EXTENSIONS.contains(extension)
    }

    /**
     * Returns the extension (the substring after the last dot) of the given file. The dot
     * is not included in the returned extension.
     *
     * @param name The filename in question.
     * @return The extension, or an empty string if no extension is found.
     */
    fun getExtension(name: String): String {
        val index = name.lastIndexOf('.')
        return if (index == -1) "" else name.substring(index + 1).lowercase(Locale.ROOT)
    }

    /**
     * Returns the base name (the substring before the last dot) of the given file. The dot
     * is not included in the returned basename.
     *
     * @param name The filename in question.
     * @return The base name, or an empty string if no basename is found.
     */
    fun getBaseName(name: String): String {
        val index = name.lastIndexOf('.')
        return if (index == -1) name else name.substring(0, index)
    }

    /**
     * Returns the file name of a .partial file of the given file.
     *
     * @param name The filename in question.
     * @return The .partial file name
     */
    fun getPartialFile(name: String): String {
        return String.format(Locale.ROOT, "%s.partial.%s", getBaseName(name), getExtension(name))
    }

    /**
     * Returns the file name of a .complete file of the given file.
     *
     * @param name The filename in question.
     * @return The .complete file name
     */
    fun getCompleteFile(name: String): String {
        return String.format(Locale.ROOT, "%s.complete.%s", getBaseName(name), getExtension(name))
    }

    @JvmStatic
    fun <T : Serializable?> serialize(context: Context, obj: T, fileName: String): Boolean {
        val file = File(context.cacheDir, fileName)
        var out: ObjectOutputStream? = null
        return try {
            out = ObjectOutputStream(FileOutputStream(file))
            out.writeObject(obj)
            Timber.i("Serialized object to %s", file)
            true
        } catch (ignored: Exception) {
            Timber.w("Failed to serialize object to %s", file)
            false
        } finally {
            Util.close(out)
        }
    }

    @Suppress("UNCHECKED_CAST")
    @JvmStatic
    fun <T : Serializable?> deserialize(context: Context, fileName: String): T? {
        val file = File(context.cacheDir, fileName)
        if (!file.exists() || !file.isFile) {
            return null
        }
        var inStream: ObjectInputStream? = null
        return try {
            inStream = ObjectInputStream(FileInputStream(file))
            val readObject = inStream.readObject()
            val result = readObject as T
            Timber.i("Deserialized object from %s", file)
            result
        } catch (all: Throwable) {
            Timber.w(all, "Failed to deserialize object from %s", file)
            null
        } finally {
            Util.close(inStream)
        }
    }

    fun savePlaylist(
        playlistFile: File?,
        playlist: MusicDirectory,
        name: String
    ) {
        val fw = FileWriter(playlistFile)
        val bw = BufferedWriter(fw)

        try {
            fw.write("#EXTM3U\n")
            for (e in playlist.getChildren()) {
                var filePath = getSongFile(e).absolutePath

                if (!File(filePath).exists()) {
                    val ext = getExtension(filePath)
                    val base = getBaseName(filePath)
                    filePath = "$base.complete.$ext"
                }
                fw.write(filePath + "\n")
            }
        } catch (e: IOException) {
            Timber.w("Failed to save playlist: %s", name)
            throw e
        } finally {
            bw.close()
            fw.close()
        }
    }
}
