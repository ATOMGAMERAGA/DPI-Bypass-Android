import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtPsiFactory
import java.io.File

/** Grammar-only check for SDK-less development. Does not substitute for Android compilation. */
fun main(args: Array<String>) {
    val disposable = Disposer.newDisposable()
    try {
        val environment = KotlinCoreEnvironment.createForProduction(disposable, CompilerConfiguration(), EnvironmentConfigFiles.JVM_CONFIG_FILES)
        val factory = KtPsiFactory(environment.project)
        var checked = 0
        var errors = 0
        File(args.firstOrNull() ?: "app/src/main/java").walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val parsed = factory.createFile(file.name, file.readText().removePrefix("\uFEFF").replace("\r\n", "\n"))
            for (error in PsiTreeUtil.findChildrenOfType(parsed, PsiErrorElement::class.java)) {
                System.err.println("${file.path}:${error.textOffset}: ${error.errorDescription}"); errors++
            }
            checked++
        }
        check(errors == 0) { "$errors Kotlin grammar errors" }
        println("Kotlin grammar: $checked files parsed, no syntax errors (not an Android build).")
    } finally { Disposer.dispose(disposable) }
}
