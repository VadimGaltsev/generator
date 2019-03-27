import org.objectweb.asm.*
import java.util.*

class KtClassTransformer(
    visitor: ClassVisitor, // делегат - пишущий - пишет
    private val annotations: Generator.Annotations, // настройки
    private val isNotVisited: (Boolean) -> Unit // если как не посещен - дергаем
) : ClassVisitor(Opcodes.ASM5, visitor) {

    private val methodAnnotation = annotations.methodSettings.methodAnnotation // аннотация для геренации
    private val whenAnnotation = annotations.methodSettings.whenAnnotation // аннотация для контрола

    // записываем сюда контро поинты, если их нашли в аннотации
    private val controlPoints = HashMap<String, String>()
    // все методы и аннотаций, которые мы хотим посетить
    private val forVisit = HashMap<String, String>()
    // все посещенные аннотации над классом
    private val classAnnotations = HashSet<String>()

    // сюда приходят все аннотации над классом
    override fun visitAnnotation(name: String, visible: Boolean): AnnotationVisitor? {
        val delegate = super.visitAnnotation(name, visible) // получаем делегата после посещения написанной аннотации
        log("Read annotation $name")
        if (name.contains(annotations.screen) /* ищем в имени нашу экранную аннотацию */) {
            log("Read annotation ${annotations.screen}")
            forVisit[annotations.screen] = "" // нашли - кладем
        }
        classAnnotations.add(name) // добавляем в аннотации, которые посетели в классе
        return KtClassAnnotationParser(delegate) // возращаем НОВЫЙ(КАЖДЫЙ РАЗ) визитор для аннотаций с пишущим ДЕЛЕГАТОМ
    }

    // парсинг аннотаций над классов
    inner class KtClassAnnotationParser(
        delegate: AnnotationVisitor?
    ) : AnnotationVisitor(Opcodes.ASM5, delegate) {

        override fun visit(name: String?, value: Any?) {
            log("Visit class annotation: name = $name and value = $value")
            if (forVisit.containsKey(annotations.screen)) { // если экран был
                forVisit[annotations.screen] = value as? String ?: "" // берем значение
            }
            super.visit(name, value) // копируем байт-код во writer
        }
    }

    // все методы класса
    override fun visitMethod(
        opcode: Int, // сумма опкодов из класса Opcodes
        name: String, // человеческие имя метода
        descriptor: String?, // дескриптор формат = (Largs;)Lreturn type;
        sig: String?, // сигнатура
        excep: Array<out String>? // исключения
    ): MethodVisitor? {
        log("Read method name: $name; descriptor: $descriptor; signature: $sig; opcode: $opcode;")
        // добавляем имя + дескриптор
        val methodName = name + descriptor
        return MethodReader( // создаем метод-визитор - передаем туда его имя и делегат
            delegate = super.visitMethod(opcode, name, descriptor, sig, excep),
            methodName = methodName
        )
    }

    // когда парсинг класса закончен и прочитан последний фрейм
    override fun visitEnd() {
        log("Class completed with class annotations :$classAnnotations and visited: $forVisit;")
        val isTargetClass = classAnnotations.contains("L${annotations.screen};") // если была аннотация с экраном
        val notVisited = !classAnnotations.contains("L${annotations.visited};") // если класс не был посещен
        log("Is class visited before: $notVisited")
        if (notVisited) {
            log("Mark as Visited with: ${annotations.visited}")
            super.visitAnnotation("L${annotations.visited};", true) // пишем аннотацию с тем что посетели
        }
        isNotVisited.invoke(notVisited && isTargetClass) // TRIGGER IT!!!!
        super.visitEnd() // ура! кончилось!
    }

    // класс читающий байт-код методов
    inner class MethodReader(
        delegate: MethodVisitor?, // MethodWriter
        private val methodName: String // наше имячко
    ) : MethodVisitor(Opcodes.ASM5, delegate) {

        // посещаем аннотацию над методом (имя и видна ли в рантайме)
        override fun visitAnnotation(name: String, visible: Boolean): AnnotationVisitor {
            log("Visit method annotation: $name")
            if (name.contains(methodAnnotation)) { // если мы имеем аннотацию из настроек и имя не пустое
                forVisit[methodName] = "" // добавляем нас в список счастливчиков
            }
            if (name.contains(whenAnnotation)) {
                controlPoints[methodName] = ""
                return KtMethodControlAnnotationReader(
                    super.visitAnnotation(name, visible),
                    methodName
                ) // посещаем аннотацию контрола
            }
            return KtMethodAnnotationParser(super.visitAnnotation(name, visible), methodName) // посещаем в любом случае
        }

        // класс читающий аннотации-генераторы над методом
        inner class KtMethodAnnotationParser(
            delegate: AnnotationVisitor?, // все так же как сверху, я устал уже писать
            private val methodName: String // нужно
        ) : AnnotationVisitor(Opcodes.ASM5, delegate) {

            override fun visit(name: String?, value: Any?) {
                log("Visit method $methodName, annotation params: name = $name and value = $value")
                if (forVisit.containsKey(methodName) && value.isString()) { // если мы счастливчик
                    // берем ЗНАЧЕНИЕ и имя - потом будет МАПА или массив если много параметров скорее всего из настроек
                    forVisit[methodName] = value as? String ?: ""
                    log(":generator:added $name; $value;")
                }
                super.visit(name, value)
            }
        }

        // класс читающий аннотации-контрола над методом
        inner class KtMethodControlAnnotationReader(
            delegate: AnnotationVisitor?, // все так же как сверху, я устал уже писать
            private val methodName: String // нужно
        ) : AnnotationVisitor(Opcodes.ASM5, delegate) {

            override fun visit(name: String?, value: Any?) {
                log(
                    "Visit method $methodName," +
                            " control annotation params: name = $name and value = $value; is str: ${value.isString()}"
                )
                if (controlPoints.containsKey(methodName) && value.isString()) {
                    controlPoints[methodName] = value as? String ?: ""
                    log("Added control point ${name ?: methodName}; $value;")
                }
                super.visit(name, value)
            }
        }

        override fun visitInsn(opcode: Int) {
            log("Visit instruction: $opcode")
            if (isNeedToGenerateWithoutControl(opcode)) generateMethodCall()
            super.visitInsn(opcode) // возвращаем опкод
        }

        override fun visitMethodInsn(
            opcode: Int,
            owner: String?,
            name: String?,
            descriptor: String?,
            isInterface: Boolean
        ) {
            log("Local method call: $owner; $name; $descriptor")
            if (isNeedToGenerateWithControl() && controlPoints[methodName] == name) {
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                log("Local method call: $owner; $name; $descriptor, start generate")
                generateMethodCall()
                return
            }
            super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
        }

        private fun generateMethodCall() {
            log("Generate Start")
            visitInsn(Opcodes.ICONST_3)
            visitVarInsn(Opcodes.ISTORE, 1)
            visitFieldInsn(
                Opcodes.GETSTATIC,
                "java/lang/System",
                "out",
                "Ljava/io/PrintStream;"
            )
            visitIntInsn(Opcodes.ILOAD, 1)
            super.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "java/io/PrintStream",
                "println",
                "(I)V",
                false
            )
            visitVarInsn(
                Opcodes.ALOAD,
                0
            ) // вот тут потом допишу когда будет нужный класс - но суть такая - открываем байт код - читаем - пишем
            visitTypeInsn(Opcodes.CHECKCAST, "android/content/Context")
            visitLdcInsn(forVisit[methodName])
            visitTypeInsn(Opcodes.CHECKCAST, "java/lang/CharSequence")
            super.visitInsn(Opcodes.ICONST_1)
            super.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "android/widget/Toast",
                "makeText",
                "(Landroid/content/Context;Ljava/lang/CharSequence;I)Landroid/widget/Toast;",
                false
            )
            super.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                "android/widget/Toast",
                "show",
                "()V",
                false
            )
            controlPoints.remove(methodName)
            forVisit.remove(methodName) // мы готовы
        }

        // проверяем opcode
        private fun isReturnOpcode(opcode: Int) = opcode in Opcodes.IRETURN..Opcodes.RETURN

        // если мы в диапозоне опкодов ретернов и контрол флоу выключен
        private fun isNeedToGenerateWithoutControl(opcode: Int) =
            !controlPoints.containsKey(methodName) && isReturnOpcode(opcode) && weAreInTarget()

        // если мы генерим в стеке вызовов конкретного метода
        private fun isNeedToGenerateWithControl() = controlPoints.containsKey(methodName) && weAreInTarget()

        // мы целевой метод
        private fun weAreInTarget() = forVisit.containsKey(methodName) // мы счастливчики
                && forVisit.containsKey(annotations.screen) // экран был
    }
    // и разве это сложно?
}

//                println("Write file before")
//                visitFieldInsn(
//                    Opcodes.GETSTATIC,
//                    "java/lang/System",
//                    "out",
//                    "Ljava/io/PrintStream;"
//                )
//                visitLdcInsn("${forVisit[methodName]} on ${forVisit[screenAnnotation]} screen")
//                visitMethodInsn(
//                    Opcodes.INVOKEVIRTUAL,
//                    "java/io/PrintStream",
//                    "println",
//                    "(Ljava/lang/Object;)V",
//                    false
//                )