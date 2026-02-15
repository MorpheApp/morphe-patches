dependencies {
    implementation(project(":extensions:shared-youtube:library"))
}

android {
    buildTypes {
        release {
            // 'libj2v8.so' is already included in the patch.
            ndk {
                abiFilters.add("")
            }
        }
    }
}
