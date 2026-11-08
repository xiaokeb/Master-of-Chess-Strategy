package com.masterofchessstrategy.data

/**
 * An owner whose rows must be admitted together, including absent-row replacement semantics.
 * Called once after all wire validation, even when this owner's kind is absent. The immutable
 * complete candidate supplies companion facts; no database writes or live handles may escape.
 */
internal interface GameBackupContainerPreparation {
    fun prepareContainer(records: List<GameBackupRecord>, context: GameBackupPreparationContext): PreparedGameBackup
}
