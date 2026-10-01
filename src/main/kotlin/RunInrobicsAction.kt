import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.util.IconLoader

class RunInrobicsAction : AnAction(
    "Ejecutar Inrobics Build",
    "Compila usando la configuración del panel lateral",
    IconLoader.getIcon("/icons/InrobicsBuild.svg", RunInrobicsAction::class.java)
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val state = InrobicsBuildState.getInstance(project)
        InrobicsCommandRunner.execute(project, state)
    }
}