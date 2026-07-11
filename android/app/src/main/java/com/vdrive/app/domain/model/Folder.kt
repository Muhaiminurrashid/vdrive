package com.vdrive.app.domain.model

data class Folder(
    val id: String = "",
    val name: String = "",
    val path: String = "",
    val parentId: String? = null,
    val createdAt: Long = 0
)
