package org.moire.ultrasonic.view

import android.content.Context
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.moire.ultrasonic.R
import org.moire.ultrasonic.domain.MusicFolder

/**
 * This little view shows the currently selected Folder (or catalog) on the music server.
 * When clicked it will drop down a list of all available Folders and allow you to
 * select one. The intended usage us to supply a filter to lists of artists, albums, etc
 */
class SelectMusicFolderView(
    private val context: Context,
    root: ViewGroup,
    private val musicFolders: List<MusicFolder>,
    private var selectedFolderId: String?,
    private val onUpdate: (String, String?) -> Unit
) : RecyclerView.ViewHolder(
    LayoutInflater.from(context).inflate(
        R.layout.select_folder_header, root, false
    )
) {
    private val folderName: TextView = itemView.findViewById(R.id.select_folder_2)
    private val layout: LinearLayout = itemView.findViewById(R.id.select_folder_header)

    init {
        if (selectedFolderId != null) {
            for ((id, name) in musicFolders) {
                if (id == selectedFolderId) {
                    folderName.text = name
                    break
                }
            }
        }
        layout.setOnClickListener { onFolderClick() }
    }

    private fun onFolderClick() {
        val popup = PopupMenu(context, layout)
        val MENU_GROUP_MUSIC_FOLDER = 10

        var menuItem = popup.menu.add(
            MENU_GROUP_MUSIC_FOLDER, -1, 0, R.string.select_artist_all_folders
        )
        if (selectedFolderId == null || selectedFolderId!!.isEmpty()) {
            menuItem.isChecked = true
        }
        for (i in musicFolders.indices) {
            val (id, name) = musicFolders[i]
            menuItem = popup.menu.add(MENU_GROUP_MUSIC_FOLDER, i, i + 1, name)
            if (id == selectedFolderId) {
                menuItem.isChecked = true
            }
        }

        popup.menu.setGroupCheckable(MENU_GROUP_MUSIC_FOLDER, true, true)

        popup.setOnMenuItemClickListener { item -> onFolderMenuItemSelected(item) }
        popup.show()
    }

    private fun onFolderMenuItemSelected(menuItem: MenuItem): Boolean {
        val selectedFolder = if (menuItem.itemId == -1) null else musicFolders[menuItem.itemId]
        val musicFolderName = selectedFolder?.name
            ?: context.getString(R.string.select_artist_all_folders)
        selectedFolderId = selectedFolder?.id

        menuItem.isChecked = true
        folderName.text = musicFolderName
        onUpdate(musicFolderName, selectedFolderId)

        return true
    }
}
