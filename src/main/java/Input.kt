import Input.Device.*
import java.awt.MouseInfo
import java.awt.Robot
import java.awt.event.KeyEvent.*
import java.awt.event.MouseEvent.*
import com.studiohartman.jamepad.*
import com.studiohartman.jamepad.ControllerButton
import com.studiohartman.jamepad.ControllerButton.*
import java.awt.event.InputEvent
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

class Input(private val workStealingPool: ExecutorService) {

    private val SENSITIVITY = 10f

    private val controllerManager: ControllerManager

    private var controllerFut: Future<Unit>
    private var controller: ControllerIndex? = null
    private val robot = Robot()
    private var currentX: Int
    private var currentY: Int

    private val activeKeys = mutableSetOf<Pair<List<Int>, Device>>()

    private val pressedKeys = mutableSetOf<Pair<List<Int>, Device>>()
    private val releasedKeys = mutableSetOf<Pair<List<Int>, Device>>()

    var enabled = AtomicBoolean(false)
    var enableCount = 0
    var enableSame = 0
    val enableDelay = 5

    private enum class Device {
        MOUSE,
        KEYBOARD
    }

    private val buttonMappings = listOf(
        DPAD_UP to KEYBOARD to listOf(VK_UP),
        DPAD_RIGHT to KEYBOARD to listOf(VK_RIGHT),
        DPAD_DOWN to KEYBOARD to listOf(VK_DOWN),
        DPAD_LEFT to KEYBOARD to listOf(VK_LEFT),
        RIGHTBUMPER to MOUSE to listOf(BUTTON1),
        LEFTBUMPER to MOUSE to listOf(BUTTON3),
        A to KEYBOARD to listOf(VK_ENTER),
        B to KEYBOARD to listOf(VK_BACK_SPACE),
        X to KEYBOARD to listOf(VK_CONTROL, VK_C),
        Y to KEYBOARD to listOf(VK_CONTROL, VK_V),
        BACK to KEYBOARD to listOf(VK_CONTROL),
        BACK to KEYBOARD to listOf(VK_CANCEL),
        START to KEYBOARD to listOf(VK_SHIFT)
    )

    init {
        controllerManager = ControllerManager()
        controllerManager.initSDLGamepad()
        val mouseLocation = MouseInfo.getPointerInfo().location
        currentX = mouseLocation.x
        currentY = mouseLocation.y
        controllerFut = workStealingPool.submit<Unit>(this::findController)
        workStealingPool.submit(updateMouseLocation(50L))
        workStealingPool.submit(handleMouseMovement(8L))
        workStealingPool.submit(handleMouseScrolling(125L))
    }

    private fun updateMouseLocation(delay: Long): () -> Unit = {
        while (true) {
            currentX = MouseInfo.getPointerInfo().location.x
            currentY = MouseInfo.getPointerInfo().location.y
            Thread.sleep(delay)
        }
    }

    private fun handleMouseScrolling(delay: Long): () -> Unit = {
        while (!controllerFut.isDone) {
            Thread.sleep(100L)
        }
        while (true) {
            if (enabled.get()) {
                val lY = floor(controller!!.getAxisState(ControllerAxis.LEFTY) * SENSITIVITY * -1f)
                if (abs(lY) > 0.01) {
                    robot.mouseWheel(lY.roundToInt() / 10)
                }
                Thread.sleep(delay)
            } else Thread.sleep(5_000L)
        }
    }

    private fun handleMouseMovement(delay: Long): () -> Unit = {
        while (!controllerFut.isDone) {
            Thread.sleep(100L)
        }
        while (true) {
            if (enabled.get()) {
                val rX = floor(controller!!.getAxisState(ControllerAxis.RIGHTX) * SENSITIVITY * 2).roundToInt()
                val rY = floor(controller!!.getAxisState(ControllerAxis.RIGHTY) * SENSITIVITY * 2 * -1f).roundToInt()
                if (abs(rX) > 1 || abs(rY) > 1) {
                    robot.mouseMove(currentX + rX * 2, currentY + rY * 2)
                }
                Thread.sleep(delay)
            } else Thread.sleep(5_000L)
        }
    }

    private fun findController() {
        while (controller == null) {
            controllerManager.update()
            val controllers =
                (0 until controllerManager.numControllers).map { controllerManager.getControllerIndex(it) }
                    .filter { it.isConnected }
                    .requireNoNulls()//XInputDevice.getAllDevices().toList().filter { it.isConnected }
            if (controllers.isEmpty()) {
                Thread.sleep(1_000L)
            } else {
                controller = controllers.first()
            }
        }
    }

    private fun handleButtons(button: ControllerButton, device: Device, keys: List<Int>) {
        if (controller!!.isButtonPressed(button)) {
            when (device) {
                MOUSE -> run {
                    val masks = keys.map(InputEvent::getMaskForButton)
                    pressedKeys.add(masks to MOUSE)

                    Thread.sleep(50L)
                    if (!controller!!.isButtonPressed(button) && !controller!!.isButtonPressed(button)) {
                        releasedKeys.add(masks to MOUSE)
                    }
                }
                KEYBOARD -> run {
                    pressedKeys.add(keys to KEYBOARD)
                    Thread.sleep(50L)
                    if (!controller!!.isButtonPressed(button) && !controller!!.isButtonPressed(button)) {
                        releasedKeys.add(keys to KEYBOARD)
                    }
                }
            }
        }
    }

    fun handleInput() {
        controllerFut.get(45L, TimeUnit.SECONDS)
        if (controller == null || !controller!!.isConnected) {
            controllerFut.cancel(true)
            controllerFut = workStealingPool.submit<Unit>(this::findController)
        }
        controllerManager.update()
        val c = controller!!
        val eBefore = enableCount
        if (c.isButtonPressed(GUIDE)) {
            enableCount++
            if (enableCount > enableDelay) {
                val current = enabled.get()
                enabled.compareAndSet(current, !current)
                enableCount = 0
                Thread.sleep(500L)
                c.doVibration(0.75f, 0.75f, 200)
                Thread.sleep(200L)
            }
        }
        if (enableCount == eBefore) {
            enableSame++
        }
        if (enableSame > 3) {
            enableSame = 0
            enableCount = 0
        }
        if (enabled.get()) {
            pressedKeys.clear()
            releasedKeys.clear()
            buttonMappings.forEach {
                handleButtons(it.first.first, it.first.second, it.second)
            }
            pressedKeys.forEach {
                if (!activeKeys.contains(it)) {
                    when(it.second) {
                        MOUSE -> it.first.forEach(robot::mousePress)
                        KEYBOARD -> it.first.forEach(robot::keyPress)
                    }
                    activeKeys.add(it)
                }
            }
            releasedKeys.forEach {
                when(it.second) {
                    MOUSE -> it.first.forEach(robot::mouseRelease)
                    KEYBOARD -> it.first.forEach(robot::keyRelease)
                }
                activeKeys.remove(it)
            }
        } else {
            Thread.sleep(300L)
        }
    }
}