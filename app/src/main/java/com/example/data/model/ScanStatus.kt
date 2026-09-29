package com.example.data.model

/**
 * Lifecycle of ONE page from capture to save.
 *
 *   CAPTURED -> PROCESSING -> PROCESSED ----------------------> READY_TO_SAVE -> SAVED
 *                         \-> NEEDS_REVIEW (edges not found) -/        ^
 *                                  \-> EDITED (crop / filter / continue) -/
 *   FAILED: the page could not be processed (original kept when it exists).
 *
 * Pages of an unsaved scan session live in CameraViewModel (keyed by raw path); the status is not
 * persisted in Room because a saved page is by definition SAVED.
 */
enum class PageStatus {
    CAPTURED,
    PROCESSING,
    PROCESSED,
    NEEDS_REVIEW,
    EDITED,
    READY_TO_SAVE,
    SAVED,
    FAILED;

    /** The page may be written to the database. */
    val isSavable: Boolean get() = this == PROCESSED || this == NEEDS_REVIEW || this == EDITED || this == READY_TO_SAVE

    /** Automatic detection failed and the user has not confirmed / fixed the page yet. */
    val needsAttention: Boolean get() = this == NEEDS_REVIEW || this == FAILED
}

/**
 * Lifecycle of a document.
 *   DRAFT  : unsaved scan session (pages only in memory + files), nothing in Room.
 *   READY  : every page of the draft is processed and none is still processing.
 *   SAVED  : persisted in Room (isTrash = false).
 *   TRASH  : persisted, isTrash = true.
 *   DELETED: removed from Room.
 * DRAFT / READY are derived from the page statuses; SAVED / TRASH from DocumentEntity.isTrash, so no
 * database migration is required.
 */
enum class DocumentStatus { DRAFT, READY, SAVED, TRASH, DELETED }

fun DocumentEntity?.documentStatus(): DocumentStatus = when {
    this == null -> DocumentStatus.DELETED
    isTrash -> DocumentStatus.TRASH
    else -> DocumentStatus.SAVED
}

fun draftStatus(pageStatuses: Collection<PageStatus>): DocumentStatus =
    if (pageStatuses.isNotEmpty() && pageStatuses.all { it.isSavable }) DocumentStatus.READY else DocumentStatus.DRAFT
