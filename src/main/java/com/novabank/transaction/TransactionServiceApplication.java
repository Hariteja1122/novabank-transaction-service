package com.novabank.transaction;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.web.config.EnableSpringDataWebSupport;
import org.springframework.scheduling.annotation.EnableScheduling;

import static org.springframework.data.web.config.EnableSpringDataWebSupport.PageSerializationMode.VIA_DTO;

@SpringBootApplication
@ConfigurationPropertiesScan
// Pages serialize as {content, page:{size, number, totalElements, totalPages}}
@EnableSpringDataWebSupport(pageSerializationMode = VIA_DTO)
@EnableScheduling // PendingTransactionReconciler
public class TransactionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TransactionServiceApplication.class, args);
    }
}
