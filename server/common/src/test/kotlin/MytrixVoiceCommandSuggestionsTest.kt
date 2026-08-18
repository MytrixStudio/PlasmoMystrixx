import org.junit.jupiter.api.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import su.plo.slib.api.position.Pos3d
import su.plo.slib.api.server.McServerLib
import su.plo.voice.server.command.Suggestions
import kotlin.test.assertContains
import kotlin.test.assertEquals

class MytrixVoiceCommandSuggestionsTest {

    @Test
    fun `suggests selector templates and online players`() {
        val player = TeamPlayer("rojo")
        val server = serverWith(player)

        val suggestions = Suggestions.playersAndSelectors(server, null, "@")

        assertContains(suggestions, "@a")
        assertContains(suggestions, "@a[team=]")
        assertContains(suggestions, "@a[team=!]")
    }

    @Test
    fun `suggests positive scoreboard teams inside team selector`() {
        val server = serverWith(
            TeamPlayer("rojo"),
            TeamPlayer("azul"),
            TeamPlayer("verde"),
        )

        val suggestions = Suggestions.playersAndSelectors(server, null, "@a[team=r")

        assertEquals(listOf("@a[team=rojo]"), suggestions)
    }

    @Test
    fun `suggests negative scoreboard teams inside team selector`() {
        val server = serverWith(
            TeamPlayer("rojo"),
            TeamPlayer("azul"),
        )

        val suggestions = Suggestions.playersAndSelectors(server, null, "@a[team=!")

        assertContains(suggestions, "@a[team=!]")
        assertContains(suggestions, "@a[team=!rojo]")
        assertContains(suggestions, "@a[team=!azul]")
    }

    private fun serverWith(vararg players: TeamPlayer): McServerLib =
        mock {
            on { this.players } doReturn players.toList()
        }

    private class TeamPlayer(
        private val teamName: String?,
    ) : MockServerPlayer(mockWorld("world"), Pos3d()) {

        fun getTeam(): FakeTeam? =
            teamName?.let(::FakeTeam)
    }

    private class FakeTeam(
        private val name: String,
    ) {

        fun getName(): String = name
    }
}
