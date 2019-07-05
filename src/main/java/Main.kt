import java.util.concurrent.Executors

object Main {
    private val workStealingPool = Executors.newWorkStealingPool()!!

    @JvmStatic
    fun main(args: Array<String>) {
        val inputController = Input(workStealingPool)
        while (true) {
            inputController.handleInput()
            Thread.sleep(10)
        }
    }
}