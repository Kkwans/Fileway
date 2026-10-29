# ass-kt bridges Kotlin types and native libass. Apply the same complete JNI
# boundary as the phone app, including companion and enum classes, to every
# consuming application rather than relying on app-local release rules.
-keep class io.github.peerless2012.ass.** { *; }
