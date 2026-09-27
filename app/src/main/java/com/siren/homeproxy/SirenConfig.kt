package com.siren.homeproxy

object SirenConfig {

    /**
     * App that SIRENHomeProxy keeps alive.
     * Example: "com.uncode.app"
     */
    const val TARGET_PACKAGE = "com.uncode.app"

    val REVIVE_ACTION: String
        get() = "$TARGET_PACKAGE.ACTION_REVIVE"

    val PROVIDER_AUTHORITY: String
        get() = "$TARGET_PACKAGE.provider"
}
