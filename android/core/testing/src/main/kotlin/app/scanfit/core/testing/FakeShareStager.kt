package app.scanfit.core.testing

import app.scanfit.core.data.export.ShareStager

/** Test double for [ShareStager]: remembers what was staged; [fails] makes staging return `null`. */
class FakeShareStager(
    var fails: Boolean = false,
) : ShareStager {
    var staged: Pair<String, ByteArray>? = null
        private set

    override suspend fun stage(
        fileName: String,
        bytes: ByteArray,
    ): String? {
        if (fails) return null
        staged = fileName to bytes.copyOf()
        return "content://test/share/$fileName"
    }
}
