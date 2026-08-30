package com.example.sompo

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.example.sompo.ui.theme.SompoVermelho
import com.example.sompo.ui.theme.CorRiscoAlto

@Composable
fun LoginScreen(viewModel: LoginViewModel, aoEntrarNoApp: () -> Unit) {
    val estado by viewModel.estado.collectAsState()

    // Edge case: assim que "Bem-vindo" aparece, segura um instante e navega sozinho pro app
    LaunchedEffect(estado) {
        if (estado is LoginUiState.BemVindo) {
            delay(1200)
            aoEntrarNoApp()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.White), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.logo_sompo), contentDescription = "Sompo", modifier = Modifier.height(90.dp))
            Spacer(Modifier.height(28.dp))
            Image(painterResource(R.drawable.logo_fiap), contentDescription = "FIAP", modifier = Modifier.height(36.dp))
            Spacer(Modifier.height(56.dp))

            when (val s = estado) {
                is LoginUiState.Ocioso -> Button(
                    onClick = { viewModel.entrar() },
                    colors = ButtonDefaults.buttonColors(containerColor = SompoVermelho)
                ) { Text("Entrar") }

                is LoginUiState.Autenticando -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = SompoVermelho)
                    Spacer(Modifier.height(8.dp))
                    Text("Acessando...", color = Color.Gray)
                }

                is LoginUiState.BemVindo -> Text(
                    "Bem-vindo, ${s.nome}!",
                    style = MaterialTheme.typography.headlineSmall,
                    color = SompoVermelho
                )

                is LoginUiState.Erro -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(s.mensagem, color = CorRiscoAlto)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.entrar() },
                        colors = ButtonDefaults.buttonColors(containerColor = SompoVermelho)
                    ) { Text("Tentar novamente") }
                }
            }
        }
    }
}
