package dev.baudet.store

import dev.baudet.project.Project

/** Projects, their folders and the per-project read/write/execute grants this app keeps. */
interface ProjectStore {
    fun createProject(name: String): Project
    fun listProjects(): List<Project>
    fun renameProject(id: String, name: String)
    /** Link a folder with a granted-rights bitmask; a null path unlinks and clears the grants. */
    fun setFolder(id: String, path: String?, perms: Int)
    fun setArchived(id: String, archived: Boolean)
    /** Delete the project and every conversation and message inside it. Files on disk are never touched. */
    fun deleteProject(id: String)
    /** The sessions under a project, newest first; a null id returns the Unassigned bucket. */
    fun sessionsIn(projectId: String?): List<SessionSummary>
    fun projectOfSession(sessionId: String): String?
}
