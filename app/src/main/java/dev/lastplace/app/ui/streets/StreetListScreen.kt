package dev.lastplace.app.ui.streets

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lastplace.app.ParkingApp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StreetListScreen(
    onAddStreet: () -> Unit,
    onEditStreet: (Long) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val container = (context.applicationContext as ParkingApp).container
    val viewModel: StreetListViewModel = viewModel(factory = StreetListViewModel.factory(container))
    val state by viewModel.uiState.collectAsState()
    val message by viewModel.message.collectAsState()

    LaunchedEffectMessage(message) {
        Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
        viewModel.clearMessage()
    }

    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) viewModel.parkWhereIAm()
        else Toast.makeText(context, "Location permission denied", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My streets") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddStreet) {
                Icon(Icons.Filled.Add, contentDescription = "Add street")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            state.active?.let { active ->
                ActiveParkingCard(active = active, onMoved = { viewModel.endParking(active.sessionId) })
            }

            if (state.active == null) {
                ExtendedFloatingActionButton(
                    text = { Text("Park where I am") },
                    icon = { Icon(Icons.Filled.LocationOn, contentDescription = null) },
                    onClick = {
                        if (container.locationProvider.hasPermission()) viewModel.parkWhereIAm()
                        else locationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }

            if (state.streets.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No streets yet. Tap + to add one.")
                }
            } else {
                LazyColumn {
                    items(state.streets, key = { it.id }) { row ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onEditStreet(row.id) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(row.name, style = MaterialTheme.typography.bodyLarge)
                                Spacer(Modifier.height(2.dp))
                                StreetSubtitle(row)
                            }
                            TextButton(onClick = { viewModel.park(row.id) }) { Text("Park here") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun StreetSubtitle(row: StreetRow) {
    val highlight = SpanStyle(
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 16.sp,
    )
    val text = buildAnnotatedString {
        val freeFor = row.freeFor
        if (freeFor == null) {
            withStyle(highlight) { append("Park anytime") }
        } else {
            append("Free for ")
            withStyle(highlight) { append(freeFor) }
            row.nextCleaningText?.let { append("  ·  $it") }
        }
    }
    Text(text)
}

@Composable
private fun LaunchedEffectMessage(message: String?, onShow: (String) -> Unit) {
    androidx.compose.runtime.LaunchedEffect(message) {
        message?.let(onShow)
    }
}

@Composable
private fun ActiveParkingCard(active: ActiveParking, onMoved: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Parked on ${active.streetName}", fontWeight = FontWeight.Bold)
            Text("Move by ${active.deadlineText}")
            Text(active.remainingText, style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClick = onMoved) { Text("I moved it") }
            }
        }
    }
}
