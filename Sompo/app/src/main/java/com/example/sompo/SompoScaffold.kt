package com.example.sompo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SompoScaffold(bleManager: BleManager, awsUploader: AwsUploader, nomeUsuario: String) {
    val navController = rememberNavController()
    val itensNav = listOf(Rota.Principal, Rota.Dashboard)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RiskAI Outliers") },
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 12.dp)) {
                        Icon(Icons.Default.AccountCircle, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(nomeUsuario, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                val backStackEntry by navController.currentBackStackEntryAsState()
                val rotaAtual = backStackEntry?.destination

                itensNav.forEach { rota ->
                    NavigationBarItem(
                        selected = rotaAtual?.hierarchy?.any { it.route == rota.caminho } == true,
                        onClick = {
                            navController.navigate(rota.caminho) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                if (rota == Rota.Principal) Icons.Default.Sensors
                                else Icons.Default.Dashboard,
                                contentDescription = rota.titulo
                            )
                        },
                        label = { Text(rota.titulo) }
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier.padding(padding)
        ) {
            SompoNavGraph(navController = navController, bleManager = bleManager, awsUploader = awsUploader)
        }
    }
}
