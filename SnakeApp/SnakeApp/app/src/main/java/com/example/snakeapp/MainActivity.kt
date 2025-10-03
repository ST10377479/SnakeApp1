package com.example.snakeapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random
import com.google.firebase.auth.FirebaseAuth
import androidx.compose.foundation.shape.RoundedCornerShape



// Data class for game state
data class State(val food: Pair<Int, Int>, val snake: List<Pair<Int, Int>>, val gameOver: Boolean)

// Data class for leaderboard entry
data class LeaderboardEntry(val username: String, val score: Int)

// Singleton object for local leaderboard
object LocalLeaderboard {
    private val entries = mutableListOf<LeaderboardEntry>()

    fun submitScore(entry: LeaderboardEntry) {
        val existing = entries.find { it.username == entry.username }
        if (existing != null) {
            if (entry.score > existing.score) {
                entries.remove(existing)
                entries.add(entry)
            }
        } else {
            entries.add(entry)
        }
        entries.sortByDescending { it.score }
        if (entries.size > 10) entries.removeAt(entries.lastIndex)
    }

    fun getTopScores(): List<LeaderboardEntry> = entries.toList()
}

// Game logic class
class Game(
    private val scope: CoroutineScope,
    private val username: String
) {

    private val mutex = Mutex()

    private val _state = MutableStateFlow(
        State(
            food = Pair(5, 5),
            snake = listOf(Pair(7, 7)),
            gameOver = false
        )
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private var snakeLength = 4
    private var move = Pair(1, 0)
    val currentMove: Pair<Int, Int>
        get() = move

    fun changeDirection(newMove: Pair<Int, Int>) {
        scope.launch {
            mutex.withLock {
                // Prevent reversing direction
                if (move.first + newMove.first != 0 || move.second + newMove.second != 0) {
                    move = newMove
                }
            }
        }
    }

    fun resetGame() {
        scope.launch {
            mutex.withLock {
                snakeLength = 4
                move = Pair(1, 0)
                _state.value = State(
                    food = Pair(Random.nextInt(BOARD_SIZE), Random.nextInt(BOARD_SIZE)),
                    snake = listOf(Pair(BOARD_SIZE / 2, BOARD_SIZE / 2)),
                    gameOver = false
                )
            }
        }
    }

    init {
        scope.launch {
            while (true) {
                delay(150)
                _state.update { currentState ->
                    if (currentState.gameOver) {
                        return@update currentState
                    }

                    val newHead = currentState.snake.first().let { head ->
                        mutex.withLock {
                            Pair(
                                head.first + move.first,
                                head.second + move.second
                            )
                        }
                    }

                    // Check if newHead is out of bounds (hits wall)
                    val hitsWall = newHead.first !in 0 until BOARD_SIZE || newHead.second !in 0 until BOARD_SIZE

                    if (hitsWall || currentState.snake.contains(newHead)) {
                        submitCurrentScore()
                        currentState.copy(gameOver = true)
                    } else {
                        val ateFood = newHead == currentState.food
                        if (ateFood) {
                            snakeLength++
                        }
                        val newFoodPos = if (ateFood) {
                            var newPos: Pair<Int, Int>
                            do {
                                newPos = Pair(Random.nextInt(BOARD_SIZE), Random.nextInt(BOARD_SIZE))
                            } while (currentState.snake.contains(newPos) || newPos == newHead)
                            newPos
                        } else currentState.food

                        currentState.copy(
                            snake = listOf(newHead) + currentState.snake.take(snakeLength - 1),
                            food = newFoodPos
                        )
                    }
                }
            }
        }
    }


    private fun submitCurrentScore() {
        val score = snakeLength - 4
        LocalLeaderboard.submitScore(LeaderboardEntry(username, score))
    }

    companion object {
        const val BOARD_SIZE = 16
    }
}

// Main Activity
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Get the username passed from SignInActivity
        val passedUsername = intent.getStringExtra("username")

        setContent {
            var username by remember { mutableStateOf(passedUsername) }

            MaterialTheme {
                if (username == null) {
                    LoginScreen(onLogin = { inputName ->
                        username = inputName.trim().takeIf { it.isNotEmpty() }
                    })
                } else {
                    // Move Game inside composable so it resets on username change
                    val game = remember(username) {
                        Game(CoroutineScope(Dispatchers.Main), username!!)
                    }
                    SnakeGameWithLeaderboard(
                        game,
                        onLogout = {
                            FirebaseAuth.getInstance().signOut()
                            username = null
                        }
                    )
                }
            }
        }
    }
}



@Composable
fun LoginScreen(onLogin: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Enter your username", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Username") }
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                if (text.isNotBlank()) {
                    onLogin(text)
                }
            }
        ) {
            Text("Login")
        }
    }
}


@Composable
fun SnakeGameWithLeaderboard(game: Game, onLogout: () -> Unit) {
    val state by game.state.collectAsState()
    val startingLength = 4
    val score = state.snake.size - startingLength
    val leaderboard = remember { mutableStateOf(LocalLeaderboard.getTopScores()) }

    LaunchedEffect(state.gameOver) {
        if (state.gameOver) {
            leaderboard.value = LocalLeaderboard.getTopScores()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween,  // Distribute content with space between top and bottom
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Score: $score",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            if (state.gameOver) {
                Text(
                    "Game Over! Tap Reset to play again.",
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = { game.resetGame() }) {
                    Text("Reset")
                }

                Spacer(Modifier.height(32.dp))
                Text("Leaderboard", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(8.dp))

                leaderboard.value.forEachIndexed { index, entry ->
                    Text("${index + 1}. ${entry.username}: ${entry.score}")
                }
            } else {
                Board(boardSize = Game.BOARD_SIZE, snake = state.snake, food = state.food)
                Spacer(Modifier.height(32.dp))
                Controls(
                    currentDirection = game.currentMove,
                    onDirectionChange = { newDir -> game.changeDirection(newDir) }
                )
            }
        }

        // Logout button placed at the bottom center
        Button(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
            Text("Logout")
        }
    }
}

@Composable
fun LeaderboardView(entries: List<LeaderboardEntry>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = "🏆 Leaderboard",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        if (entries.isEmpty()) {
            Text("No scores yet.", style = MaterialTheme.typography.bodyLarge)
        } else {
            entries
                .sortedByDescending { it.score } // Highest score first
                .forEachIndexed { index, entry ->
                    LeaderboardRow(rank = index + 1, username = entry.username, score = entry.score)
                }
        }
    }
}

@Composable
fun LeaderboardRow(rank: Int, username: String, score: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$rank.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.width(32.dp)
        )
        Text(
            text = username,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "$score pts",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.align(Alignment.CenterVertically)
        )
    }
}


@Composable
fun Board(boardSize: Int, snake: List<Pair<Int, Int>>, food: Pair<Int, Int>) {
    val tileSize = 20.dp

    Box(
        Modifier
            .size(tileSize * boardSize)
            .border(2.dp, Color.DarkGray)
            .background(Color(0xFFE0E0E0))
    ) {
        Box(
            Modifier
                .offset(x = tileSize * food.first, y = tileSize * food.second)
                .size(tileSize)
                .background(Color.Red, CircleShape)
        )
        snake.forEach { pos ->
            Box(
                Modifier
                    .offset(x = tileSize * pos.first, y = tileSize * pos.second)
                    .size(tileSize)
                    .background(Color.Green)
            )
        }
    }
}

@Composable
fun Controls(
    currentDirection: Pair<Int, Int>,
    onDirectionChange: (Pair<Int, Int>) -> Unit
) {
    val buttonSize = Modifier.size(56.dp)

    fun isActiveDirection(dir: Pair<Int, Int>) = dir == currentDirection

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = { onDirectionChange(Pair(0, -1)) },
            modifier = buttonSize,
            colors = if (isActiveDirection(Pair(0, -1)))
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            else
                ButtonDefaults.buttonColors()
        ) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Up")
        }
        Row {
            Button(
                onClick = { onDirectionChange(Pair(-1, 0)) },
                modifier = buttonSize,
                colors = if (isActiveDirection(Pair(-1, 0)))
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                else
                    ButtonDefaults.buttonColors()
            ) {
                Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Left")
            }
            Spacer(Modifier.size(56.dp))
            Button(
                onClick = { onDirectionChange(Pair(1, 0)) },
                modifier = buttonSize,
                colors = if (isActiveDirection(Pair(1, 0)))
                    ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                else
                    ButtonDefaults.buttonColors()
            ) {
                Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Right")
            }
        }
        Button(
            onClick = { onDirectionChange(Pair(0, 1)) },
            modifier = buttonSize,
            colors = if (isActiveDirection(Pair(0, 1)))
                ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            else
                ButtonDefaults.buttonColors()
        ) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Down")
        }
    }
}
