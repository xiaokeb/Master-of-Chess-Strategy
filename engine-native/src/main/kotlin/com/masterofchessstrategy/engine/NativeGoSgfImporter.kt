package com.masterofchessstrategy.engine

/** Resolves the supported board size via the real parser, not a regex over comments. */
object NativeGoSgfImporter {
    fun importSgf(text: String): GoSgfImportResult {
        if (text.isEmpty() || GoSgfTextCodec.encodeOrNull(text) == null)
            return GoSgfImportResult.Rejected(EngineError.CORRUPTED_DATA)
        var lastError = EngineError.CORRUPTED_DATA
        for (size in listOf(9, 13, 19)) {
            val candidate = NativeGoEngine(boardSize = size)
            var transferred = false
            try {
                when (val result = candidate.restoreSgf(text)) {
                    RestoreResult.Restored -> {
                        transferred = true
                        return GoSgfImportResult.Imported(candidate)
                    }
                    is RestoreResult.Rejected -> lastError = result.error
                }
            } finally {
                if (!transferred) candidate.close()
            }
        }
        return GoSgfImportResult.Rejected(lastError)
    }
}
