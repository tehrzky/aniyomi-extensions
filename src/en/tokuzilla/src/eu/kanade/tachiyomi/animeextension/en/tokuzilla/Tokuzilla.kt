Task :lib:chillx-extractor:bundleLibCompileToJarRelease
> Task :lib:chillx-extractor:syncReleaseLibJars FROM-CACHE
> Task :lib:chillx-extractor:bundleReleaseLocalLintAar
> Task :lib:chillx-extractor:generateReleaseLintModel
/home/runner/work/aniyomi-extensions/aniyomi-extensions/src/en/tokuzilla/src/eu/kanade/tachiyomi/animeextension/en/tokuzilla/Tokuzilla.kt:164:20: Lint error > [standard:wrapping] Missing newline after "("

/home/runner/work/aniyomi-extensions/aniyomi-extensions/src/en/tokuzilla/src/eu/kanade/tachiyomi/animeextension/en/tokuzilla/Tokuzilla.kt:169:13: Lint error > [standard:wrapping] Missing newline before ")"
> Task :src:en:tokuzilla:lintKotlinMain FAILED
gradle/actions: Writing build results to /home/runner/work/_temp/.gradle-actions/build-results/__run_2-1760855253813.json

[Incubating] Problems report is available at: file:///home/runner/work/aniyomi-extensions/aniyomi-extensions/build/reports/problems/problems-report.html


FAILURE: Build failed with an exception.

* What went wrong:
Deprecated Gradle features were used in this build, making it incompatible with Gradle 9.0.
Execution failed for task ':src:en:tokuzilla:lintKotlinMain'.

> lintKotlinMain sources failed lint check
You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.


* Try:
For more on this, please refer to https://docs.gradle.org/8.13/userguide/command_line_interface.html#sec:command_line_warnings in the Gradle documentation.
120 actionable tasks: 76 executed, 44 from cache
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to get full insights.
> Get more help at https://help.gradle.org.

BUILD FAILED in 41s
Error: Process completed with exit code 1.
