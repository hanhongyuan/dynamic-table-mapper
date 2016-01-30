package com.closer.common.handler;

import com.closer.common.config.RDMSConfig;
import com.closer.tenant.domain.Tenant;
import com.closer.tenant.event.TenantCreateEvent;
import com.closer.tenant.service.TenantSupport;
import org.hibernate.cfg.Configuration;
import org.hibernate.cfg.ImprovedNamingStrategy;
import org.hibernate.dialect.Dialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 表处理帮助类
 * Created by closer on 2016/1/5.
 */
@Component
public class TableHandler {


    private Logger log = LoggerFactory.getLogger(TableHandler.class);

    @Autowired
    private DataSource dataSource;

    @Autowired
    private List<TenantSupport> tenantSupports;

    @Autowired
    private ApplicationContext applicationContext;

    private Set<Class> entityClasses;

    @PostConstruct
    public void postConstruct() {
        entityClasses = new HashSet<>();
        for (TenantSupport tenantSupport : tenantSupports) {
            entityClasses.addAll(tenantSupport.getEntities());
        }
    }

    /**
     * 处理公司新增事件，该处理将于公司新增的保存提交后执行
     *
     * @param event 事件
     */

    @TransactionalEventListener
    public void handleCompanyCreate(TenantCreateEvent event) {
        TableHandler self = applicationContext.getBean(TableHandler.class);
        self.createTable(entityClasses, event.getTenant());
    }


    @Async
    public void createTable(Set<Class> entityClasses, Tenant tenant) {
        System.out.println(Thread.currentThread().getName());
        TableProvider.setTenant(tenant);
        _createTable(entityClasses, TableProvider.PREFIX, " IF NOT EXISTS " + TableProvider.getTablePrefix());
        TableProvider.clear();
    }


    private void _createTable(Set<Class> entityClasses, String... keyValues) {
        if (keyValues.length % 2 == 1) {
            throw new RuntimeException("创建表时需要替换的字符与值应成对出现");
        }

        String[] sqlArr = entity2Sql(RDMSConfig.DIALECT, entityClasses);

        Connection connection = DataSourceUtils.getConnection(dataSource);
        try (Statement stmt = connection.createStatement()) {
            for (String sql : sqlArr) {
                for (int i = 0; i < keyValues.length; i = i + 2) {
                    String key = keyValues[i];
                    String value = keyValues[i + 1];
                    sql = sql.replace(key, value);
                }
                log.info("建表sql:{}", sql);
                stmt.execute(sql);
            }
        } catch (SQLException e) {
            String msg = "创建表失败";
            throw new RuntimeException(msg, e);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private String[] entity2Sql(Dialect dialect, Set<Class> entityClasses) {
        Configuration cfg = new Configuration();
        cfg.setNamingStrategy(new ImprovedNamingStrategy());
        for (Class entityClass : entityClasses) {
            cfg.addAnnotatedClass(entityClass);
        }
        return cfg.generateSchemaCreationScript(dialect);
    }
}
