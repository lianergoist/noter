package dk.rpix.noter.ui

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dk.rpix.noter.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ny note-knappen skal kunne læses af TalkBack: én knap med navnet "Ny note", der kan klikkes.
 * Uden eksplicit semantik var knappen navnløs (uiautomator markerede den NAF).
 */
@RunWith(AndroidJUnit4::class)
class NewNoteButtonTest {
    @get:Rule val compose = createComposeRule()

    private val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.new_note)

    @Test fun hasNameRoleAndClickAction() {
        var clicks = 0
        compose.setContent { NoterTheme { NewNoteButton(onClick = { clicks++ }) } }

        val button = hasContentDescription(label) and hasClickAction() and
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
        compose.onNode(button).assertExists().performClick()
        assertEquals("klik via tilgængelighedsnoden åbner en ny note", 1, clicks)
    }

    @Test fun nameIsReadOnlyOnce() {
        compose.setContent { NoterTheme { NewNoteButton(onClick = {}) } }
        // Én node med navnet, og teksten må ikke også stå som sin egen node (så læses den to gange)
        compose.onAllNodesWithContentDescription(label).assertCountEquals(1)
        compose.onAllNodesWithText(label).assertCountEquals(0)
    }
}
