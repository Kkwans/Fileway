# Android core-library desugaring

- Runtime artifact: `com.android.tools:desugar_jdk_libs:2.1.5`.
- Upstream: https://github.com/google/desugar_jdk_libs
- Needed by CommonMark 0.30.1's Java collection factory calls on Android API 29; the app retains its existing JDK17 and minSdk29.
- OpenJDK GPL v2 with the Classpath Exception; preserve the full `desugar-jdk-LICENSE.txt` and `desugar-jdk-ASSEMBLY_EXCEPTION.txt`. The runtime distribution also contains Android/Google implementation portions under their upstream Apache/BSD notices.
- The D8/R8 build provides the runtime's API rewrites; no downloaded code or new runtime permission is introduced.
