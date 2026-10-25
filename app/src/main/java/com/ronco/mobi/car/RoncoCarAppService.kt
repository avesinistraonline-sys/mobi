package com.ronco.mobi.car

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

/**
 * Porta de entrada do Android Auto. O Android Auto liga neste servico quando o celular esta
 * conectado ao carro e voce abre "Ronco Mobi" no launcher do multimidia.
 */
class RoncoCarAppService : CarAppService() {
    // Uso pessoal (APK instalado fora da Play Store): aceita qualquer host do Android Auto.
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = RoncoSession()
}
