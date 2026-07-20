package com.orquestrador.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Liga o agendamento. <b>O tamanho da pool NÃO está aqui</b> — está em
 * {@code spring.task.scheduling.pool.size} no {@code application.yml}, com o racional junto.
 *
 * <p>O ponteiro é de propósito: até a ADR-0050 este arquivo era um {@code @EnableScheduling} pelado
 * e a pool valia <b>1 por omissão</b>, sem que nada dissesse isso em lugar nenhum. Os seis
 * {@code @Scheduled} do {@code HeartbeatScheduler} dividiam {@code scheduling-1} e ninguém sabia. Um
 * leitor que venha procurar configuração de scheduler chega aqui primeiro; ele não pode sair daqui
 * achando que não há decisão tomada.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
