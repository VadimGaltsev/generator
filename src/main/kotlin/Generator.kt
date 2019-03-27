import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.objectweb.asm.*
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

private const val CLASS_EXTENSION = ".class"
private const val PLUGIN_SETTINGS_NAME = "generator"
private const val COMPILE_STAGE = "compile"
private const val STUB_DEFAULT = "stub"
private var debugMode = false
// меняем все точки на / т.к. в байт-коде все дискрипторы имеют вид com/example/project/Class
fun replaceDot(string: String) = string.replace(".", "/")

// если дебаг в настройках - пишем лог
fun log(message: String) {
    if (debugMode) {
        println("$ANSI_GREEN$message$ANSI_RESET")
    }
}

// настройки для метода
open class MethodSettings {
    // название класса аннотации
    var methodAnnotation: String = STUB_DEFAULT
        set(value) {
            field = replaceDot(value)
        }

    // аннотация для обработки
    var whenAnnotation = STUB_DEFAULT
        set(value) {
            field = replaceDot(value)
        }

    override fun toString(): String {
        return "MethodSettings: $methodAnnotation; isControlFlow: $whenAnnotation; "
    }
}

class Generator : Plugin<Project> {

    /* класс для настроек градл плагина */
    open class Annotations {
        var screen = STUB_DEFAULT // название класса аннотации с пакетом для экрана
            set(value) {
                field = replaceDot(value)
            }
        var methodSettings =
            MethodSettings() // настройки для методов (принимаем название и состояние контролфлоу)
        var visited = "" // название класса аннотации с пакетом для маркировки посещенных классов
            set(value) {
                field = replaceDot(value)
            }
        var logging = false

        override fun toString(): String {
            return "screen: $screen; method: $methodSettings; visited: $visited;"
        }

        // метод вызывается для настройки аннотаций над методами
        fun methodConfig(action: Action<MethodSettings>) {
            action.execute(methodSettings)
        }
    }

    override fun apply(project: Project) {
        // Создаем наши настройки в виде расширение и добавляем их в проект
        val extension = project.extensions.create(PLUGIN_SETTINGS_NAME, Annotations::class.java)
        // берем наши таски
        project.tasks
            .withType(KotlinCompile::class.java) // ищем таски котлин компилятора
            .whenObjectAdded { compileTask ->
                // когда объект готов
                compileTask.doLast { task ->
                    // добавляем в последний шаг анализ и генерацию кода
                    if (/* если таcк компиляции */ task.name.contains(COMPILE_STAGE, true)) {
                        task as KotlinCompile // тут надеюсь все понятно
                        task.outputs.files.forEach { file ->
                            // берем таску и ее исходящую директорию и все файлы/папки
                            debugMode = extension.logging // смотрим настройки
                            log(":generator:attach to kotlin compiler with settings:")
                            log(":generator:$extension")
                            generate(file, extension) // ду меджик
                        }
                    }
                }
            }
    }

    private fun generate(path: File, annotations: Annotations) {
        Files.walk(path.toPath()) // начинаем посещать все папки
            .filter { filteredPath -> Files.isRegularFile(filteredPath) } // ищем файлы и только файлы
            .parallel() // ПАРАЛЛЕЛ МЛЕА
            .forEach { f ->
                // ищем класс файлы
                if ("${f.fileName}".contains(CLASS_EXTENSION, true)) {
                    //создаем класс ридер, который парсит файл
                    ClassReader(f.toFile().inputStream()).apply {
                        log(":generator:start visit ${f.fileName}")
                        // создем пишущий объект и передаем туда читателя, чтобы дописывать этот класс, используя в виде делегата
                        val writer = ClassWriter(
                            this,
                            ClassWriter.COMPUTE_MAXS /* пусть сам разбирается со стеком и локальными переменными */
                        )
                        // наш трансформер - передаем туда пишущий визитор
                        KtClassTransformer(writer, annotations) { shouldWrite ->
                            log(":generator:file transformed ${f.fileName} == $shouldWrite")
                            // если класс не посещали ранее
                            if (shouldWrite) // ПИШЕМ!
                                Files.write(
                                    Paths.get(f.toFile().absolutePath),
                                    writer.toByteArray()
                                )
                        }.also { transformer ->
                            log(":generator:transform")
                            // передаем визитора в читатель; цепочка такая - парсит класс - ридер -> копирует код писатель -> визитор аналирзирует
                            this.accept(transformer, 0)
                        }
                    }
                }
            }
    }
}
