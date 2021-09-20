package de.saschahlusiak.freebloks.game.finish

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.animation.*
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import de.saschahlusiak.freebloks.R
import de.saschahlusiak.freebloks.analytics
import de.saschahlusiak.freebloks.game.OnStartCustomGameListener
import de.saschahlusiak.freebloks.model.GameMode
import de.saschahlusiak.freebloks.model.PlayerScore
import de.saschahlusiak.freebloks.model.colorOf
import de.saschahlusiak.freebloks.statistics.StatisticsActivity
import de.saschahlusiak.freebloks.utils.MaterialDialog
import de.saschahlusiak.freebloks.utils.MaterialDialogFragment
import kotlinx.android.synthetic.main.game_finish_fragment.*
import kotlinx.android.synthetic.main.game_finish_player_row.view.*
import java.lang.IllegalStateException

class GameFinishFragment : MaterialDialogFragment(R.layout.game_finish_fragment), View.OnClickListener {

    private val viewModel by lazy { ViewModelProvider(this).get(GameFinishFragmentViewModel::class.java) }
    private val gameHelper by lazy { viewModel.gameHelper }

    private val listener get() = requireActivity() as OnStartCustomGameListener

    // TODO: support a light dialog theme variant?
    override fun getTheme() = R.style.Theme_Freebloks_Dialog_MinWidth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!viewModel.isInitialised()) {
            viewModel.setDataFromBundle(requireArguments())
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val data = viewModel.data
        val gameMode = viewModel.gameMode

        if (data != null && gameMode != null) {
            updateViews(data, gameMode)
        } else {
            throw IllegalStateException("data or mode is null")
        }

        new_game.setOnClickListener(this)
        show_main_menu.setOnClickListener(this)
        statistics.setOnClickListener(this)
        achievements.setOnClickListener(this)
        leaderboard.setOnClickListener(this)

        viewModel.isSignedIn.observe(viewLifecycleOwner, Observer { signedIn ->
            dialog?.window?.let { viewModel.gameHelper.setWindowForPopups(it) }
            if (signedIn) {
                viewModel.unlockAchievements()
            }
            achievements.visibility = if (signedIn) View.VISIBLE else View.GONE
            leaderboard.visibility = if (signedIn) View.VISIBLE else View.GONE
        })
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return MaterialDialog(requireContext(), theme).apply {
            supportRequestWindowFeature(Window.FEATURE_NO_TITLE)
            setCanceledOnTouchOutside(false)
        }
    }

    override fun onClick(v: View) {
        when (v.id) {
            R.id.new_game -> {
                analytics.logEvent("finish_new_game_click")
                dismiss()
                listener.startNewDefaultGame()
            }
            R.id.show_main_menu -> {
                analytics.logEvent("finish_main_menu_click")
                dismiss()
                listener.showMainMenu()
            }
            R.id.statistics -> {
                analytics.logEvent("finish_statistics_click")
                val intent = Intent(requireContext(), StatisticsActivity::class.java)
                startActivity(intent)
            }
            R.id.achievements -> {
                analytics.logEvent("finish_achievements_click")
                gameHelper.startAchievementsIntent(this, REQUEST_ACHIEVEMENTS)
            }
            R.id.leaderboard -> {
                analytics.logEvent("finish_leaderboard_click")
                gameHelper.startLeaderboardIntent(this, getString(R.string.leaderboard_points_total), REQUEST_LEADER_BOARD)
            }
        }
    }

    private fun updateViews(data: List<PlayerScore>, gameMode: GameMode) {
        val t = arrayOf(place1, place2, place3, place4)

        when (gameMode) {
            GameMode.GAMEMODE_2_COLORS_2_PLAYERS,
            GameMode.GAMEMODE_DUO,
            GameMode.GAMEMODE_JUNIOR,
            GameMode.GAMEMODE_4_COLORS_2_PLAYERS -> {
                t[2].visibility = View.GONE
                t[3].visibility = View.GONE
            }
            GameMode.GAMEMODE_4_COLORS_4_PLAYERS -> {
                t[2].visibility = View.VISIBLE
                t[3].visibility = View.VISIBLE
            }
        }
        place.setText(R.string.game_finished)

        var i = data.size - 1
        while (i >= 0) {
            val row = t[i]

            row.name.text = data[i].clientName
            row.name.clearAnimation()
            row.place.text = String.format("%d.", data[i].place)

            var s = resources.getQuantityString(R.plurals.number_of_points, data[i].totalPoints, data[i].totalPoints)
            row.points.text = s

            s = if (data[i].bonus > 0) " (+" + data[i].bonus + ")" else ""

            row.bonus_points.text = s
            row.stones.text = resources.getQuantityString(R.plurals.number_of_stones_left, data[i].stonesLeft, data[i].stonesLeft)
            row.data.setBackground(getScoreDrawable(data[i], gameMode))

            val set = AnimationSet(false)

            AlphaAnimation(0.0f, 1.0f).apply {
                startOffset = i * 100L
                duration = 600
                fillBefore = true
            }.also { set.addAnimation(it) }

            TranslateAnimation(
                TranslateAnimation.RELATIVE_TO_SELF, 1.0f,
                TranslateAnimation.RELATIVE_TO_SELF, 0.0f,
                TranslateAnimation.RELATIVE_TO_SELF, 0.0f,
                TranslateAnimation.RELATIVE_TO_SELF, 0.0f
            ).apply {
                startOffset = 200 + i * 100.toLong()
                duration = 600
                fillBefore = true
            }.also { set.addAnimation(it) }

            if (data[i].isLocal) {
                place.text = resources.getStringArray(R.array.places)[data[i].place - 1]
                row.name.setTextColor(Color.WHITE)
                row.place.setTextColor(Color.WHITE)
                row.name.setTypeface(Typeface.DEFAULT_BOLD)
                row.stones.setTextColor(Color.WHITE)

                TranslateAnimation(
                    TranslateAnimation.RELATIVE_TO_SELF, 0.0f,
                    TranslateAnimation.RELATIVE_TO_SELF, 0.4f,
                    TranslateAnimation.RELATIVE_TO_SELF, 0.0f,
                    TranslateAnimation.RELATIVE_TO_SELF, 0.0f
                ).apply {
                    duration = 300
                    interpolator = DecelerateInterpolator()
                    repeatMode = Animation.REVERSE
                    repeatCount = Animation.INFINITE
                }.also { row.name.startAnimation(it) }

                AlphaAnimation(0.5f, 1.0f).apply {
                    duration = 750
                    interpolator = LinearInterpolator()
                    repeatMode = Animation.REVERSE
                    repeatCount = Animation.INFINITE
                }.also { set.addAnimation(it) }
            }
            row.data.startAnimation(set)

            i--
        }
    }

    private fun getScoreDrawable(data: PlayerScore, gameMode: GameMode): Drawable {
        val l = when {
            data.color2 >= 0 -> resources.getDrawable(R.drawable.bg_card_2)
            else -> resources.getDrawable(R.drawable.bg_card_1)
        }.mutate() as LayerDrawable

        var color = gameMode.colorOf(data.color1)
        val grad1 = l.findDrawableByLayerId(R.id.color1) as GradientDrawable
        grad1.setColor(resources.getColor(color.backgroundColorId))

        if (data.color2 >= 0) {
            color = gameMode.colorOf(data.color2)
            val grad2 = l.findDrawableByLayerId(R.id.color2) as GradientDrawable
            grad2.setColor(resources.getColor(color.backgroundColorId))
        }

        return l
    }

    companion object {
        private const val REQUEST_ACHIEVEMENTS = 1000
        private const val REQUEST_LEADER_BOARD = 1001
    }
}