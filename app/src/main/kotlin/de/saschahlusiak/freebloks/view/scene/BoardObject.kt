package de.saschahlusiak.freebloks.view.scene

import de.saschahlusiak.freebloks.model.GameMode
import de.saschahlusiak.freebloks.utils.PointF
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.pow

/**
 * The board object that is rendered on the screen. Responsible for rendering the board and invidiudal stones, but not the actual field.
 *
 * The board is always rendered in the middle of the model coordinates, with yellow being
 * top-left (-10/-10) and blue being bottom-left (-10/10).
 *
 * It is then the *camera* that is rotated by [baseAngle] to focus on the [basePlayer].
 *
 * All model coordinates in touch events in [SceneElement] are above model coordinates.
 */
class BoardObject(private val scene: Scene, var lastSize: Int) : SceneElement {
    /**
     * This is the amount the board is rotated when touching. Note that this is rotated around the baseAngle, so 0.0f means
     * to center on the [basePlayer] using the camera angle of [baseAngle].
     */
    var currentAngle = 0.0f

    /**
     * When rotating and the pointer is released, this is the target angle to "settle" the board to.
     */
    internal var targetAngle = 0f

    /**
     * True if the user is currently touching and rotating the board
     */
    internal var rotating = false

    /**
     * If true, the board will automatically rotate in [execute] when the game is finished.
     */
    private var autoRotate = true

    /**
     * The last angle of the last touch event to the center of the board.
     * Used to calculate the new [currentAngle] when rotating.
     */
    private var lastTouchAngle = 0f

    /**
     * Stores the coordinates of the first "down" event, to detect whether we have moved at all when handling the "up" event
     */
    private var originalTouchPoint = PointF()

    /**
     * TODO: document me
     */
    internal var lastDetailsPlayer = -1

    fun updateDetailsPlayer() {
        val p = if (currentAngle > 0) (currentAngle.toInt() + 45) / 90 else (currentAngle.toInt() - 45) / 90
        lastDetailsPlayer = if (currentAngle < 10.0f && currentAngle >= -10.0f) -1 else (scene.basePlayer + p + 4) % 4
        val game = scene.game
        if (game.gameMode === GameMode.GAMEMODE_2_COLORS_2_PLAYERS || game.gameMode === GameMode.GAMEMODE_DUO || game.gameMode === GameMode.GAMEMODE_JUNIOR) {
            if (lastDetailsPlayer == 1) lastDetailsPlayer = 0
            if (lastDetailsPlayer == 3) lastDetailsPlayer = 2
        }
        scene.setShowPlayerOverride(showDetailsPlayer, lastDetailsPlayer >= 0)
    }

    /**
     * returns the number of the player whose seeds are to be shown
     *
     * @return -1 if seeds are disabled
     * detail player if board is rotated
     * current player, if local
     * -1 otherwise
     */
    val showSeedsPlayer: Int
        get() {
            if (!scene.showSeeds) return -1
            if (lastDetailsPlayer >= 0) return lastDetailsPlayer
            if (scene.game.isFinished) return scene.basePlayer
            return if (scene.game.isLocalPlayer()) scene.game.currentPlayer else -1
        }

    /**
     * Returns the player, whose details are to be shown.
     *
     * @return player, 0..3, never -1
     */
    // TODO: would be nice to show the last current local player instead of the center one
    //       needs caching of previous local player */
    private val showDetailsPlayer: Int
        get() {
            if (lastDetailsPlayer >= 0) return lastDetailsPlayer
            if (!scene.game.isStarted) return -1
            if (scene.game.isFinished) return scene.basePlayer
            return if (scene.game.currentPlayer >= 0) scene.game.currentPlayer else scene.basePlayer
        }

    /**
     * The player that should be shown on the wheel.
     *
     * @return number between 0 and 3
     */
    // TODO: would be nice to show the last current local player instead of the center one
	//       needs caching of previous local player */
    val showWheelPlayer: Int
        get() {
            if (lastDetailsPlayer >= 0) return lastDetailsPlayer
            if (scene.game.isFinished) {
                return scene.basePlayer
            }
            return if (scene.game.isLocalPlayer() || scene.showOpponents) scene.game.currentPlayer else scene.basePlayer
        }

    override fun handlePointerDown(m: PointF): Boolean {
        lastTouchAngle = atan2(m.y, m.x)
        originalTouchPoint = m
        rotating = true
        autoRotate = false
        return true
    }

    override fun handlePointerMove(m: PointF): Boolean {
        if (!rotating) return false

        scene.currentStone.stopDragging()
        val newAngle = atan2(m.y, m.x)
        currentAngle += (lastTouchAngle - newAngle) / Math.PI.toFloat() * 180.0f
        lastTouchAngle = newAngle
        while (currentAngle >= 180.0f) currentAngle -= 360.0f
        while (currentAngle <= -180.0f) currentAngle += 360.0f

        updateDetailsPlayer()
        val s = showWheelPlayer
        if (scene.wheel.currentPlayer != s) {
            scene.wheel.update(s)
        }

        scene.invalidate()
        return true
    }

    override fun handlePointerUp(m: PointF) {
        if (!rotating) return

        if (abs(m.x - originalTouchPoint.x) < 1 && abs(m.y - originalTouchPoint.y) < 1) {
            resetRotation()
        } else {
            targetAngle = if (currentAngle > 0)
                ((currentAngle.toInt() + 45) / 90 * 90).toFloat()
            else
                ((currentAngle.toInt() - 45) / 90 * 90).toFloat()
        }
        rotating = false
    }

    fun resetRotation() {
        targetAngle = 0.0f
        autoRotate = true
        lastDetailsPlayer = -1
    }

    override fun execute(elapsed: Float): Boolean {
        if (!rotating && scene.game.isFinished && autoRotate) {
            val autoRotateSpeed = 25.0f // degrees / second
            currentAngle += elapsed * autoRotateSpeed

            while (currentAngle >= 180.0f) currentAngle -= 360.0f
            while (currentAngle <= -180.0f) currentAngle += 360.0f

            updateDetailsPlayer()
            val s = showWheelPlayer
            if (scene.wheel.currentPlayer != s) {
                scene.wheel.update(s)
            }

            return true
        } else if (!rotating && abs(currentAngle - targetAngle) > 0.05f) {
            val snapSpeed = 10.0f + abs(currentAngle - targetAngle).pow(0.65f) * 30.0f

            var lp = scene.wheel.currentPlayer
            if (currentAngle - targetAngle > 0.1f) {
                currentAngle -= elapsed * snapSpeed
                if (currentAngle - targetAngle <= 0.1f) {
                    currentAngle = targetAngle
                    lp = -1
                }
            }
            if (currentAngle - targetAngle < -0.1f) {
                currentAngle += elapsed * snapSpeed
                if (currentAngle - targetAngle >= -0.1f) {
                    currentAngle = targetAngle
                    lp = -1
                }
            }
            updateDetailsPlayer()
            val s = showWheelPlayer
            if (lp != s) {
                scene.wheel.update(s)
            }
            return true
        }
        return false
    }
}