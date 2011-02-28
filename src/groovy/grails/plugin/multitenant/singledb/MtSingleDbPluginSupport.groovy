package grails.plugin.multitenant.singledb

import grails.plugin.hibernatehijacker.hibernate.HibernateEventSubscriptionFactory
import grails.plugin.multitenant.core.MultiTenantService
import grails.plugin.multitenant.core.Tenant
import grails.plugin.multitenant.core.exception.TenantException
import grails.plugin.multitenant.core.impl.CurrentTenantThreadLocal
import grails.plugin.multitenant.core.servlet.CurrentTenantServletFilter
import grails.plugin.multitenant.core.spring.*
import grails.plugin.multitenant.singledb.hibernate.*

import org.codehaus.groovy.grails.commons.ConfigurationHolder
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.CustomScopeConfigurer
import org.springframework.context.ApplicationContext
import org.springframework.orm.hibernate3.FilterDefinitionFactoryBean

/**
 * Used by the plugin descriptor.
 * ---
 * Having this code in a separate file provides separation
 * between plugin meta data and logic, makes it easier to test
 * and better code completion in STS. 
 * 
 * @author Kim A. Betti
 */
class MtSingleDbPluginSupport {
    
    private static final Logger log = LoggerFactory.getLogger(this) 

    static doWithSpring = {
                
        // Default CurrentTenant implementation storing
        // the current tenant id in a ThreadLocal variable.
        currentTenant(CurrentTenantThreadLocal) {
            eventBroker = ref("eventBroker")
        }
        
        // Injects currentTenant into beans implementing CurrentTenantAware
        currentTenantAwarePostProcessor(CurrentTenantAwarePostProcessor) {
            currentTenant = ref("currentTenant")
        }

        // A custom Spring scope for beans.
        tenantScope(TenantScope) {
            currentTenant = ref("currentTenant")
        }

        // Set per-tenant beans up in the custom tenant scope
        configuredTenantBeanProcessor(ConfiguredTenantScopedBeanProcessor) {
            perTenantBeans = ConfigurationHolder.config?.multiTenant?.perTenantBeans ?: []
        }
        
        // Responsible for registering the custom 'tenant' scope with Spring.
        tenantScopeConfigurer(CustomScopeConfigurer) {
            scopes = [ tenant: ref("tenantScope") ]
        }
        
        // Definition of the Hibernate filter making sure that
        // each tenant only sees and touches its own data.
        multiTenantHibernateFilter(FilterDefinitionFactoryBean) {
            defaultFilterCondition = ":tenantId = tenant_id"
            parameterTypes = [ tenantId: "java.lang.Integer" ]
        }
        
        // Listens for new Hibernate sessions and enables the
        // multi-tenant filter with the current tenant id.
        tenantHibernateFilterEnabler(TenantHibernateFilterEnabler) {
            multiTenantHibernateFilter = ref("multiTenantHibernateFilter")
            currentTenant = ref("currentTenant")
            sessionFactory = ref("sessionFactory")
        }

        // Inserts tenantId, makes sure that we're not
        // loading other tenant's data and so on
        tenantHibernateEventListener(HibernateEventSubscriptionFactory) {
            eventListener = { TenantHibernateEventListener listener ->
                currentTenant = ref("currentTenant")
            }
        }

        // Enables the tenant filter for our domain classes
        tenantFilterConfigurator(TenantHibernateFilterConfigurator) {
            multiTenantHibernateFilter = ref("multiTenantHibernateFilter")
        }

        // Listens for new, removed and updated tenants and broadcasts
        // the information using Hawk Eventing making it easier to
        // listen in on these events.
        def multiTenantConfig = ConfigurationHolder.config.multitenant
        tenantHibernateEventProxy(TenantHibernateEventProxy) {
            tenantClass = multiTenantConfig?.tenantClass ?: null
            eventBroker = ref("eventBroker")
        }

    }
    
    static doWithDynamicMethods = { ApplicationContext ctx ->
        createMethodsOnTenantClass(ctx)
        createWithTenantIdMethod(Tenant, ctx.multiTenantService)
        createWithoutTenantRestrictionMethod(Tenant, ctx.multiTenantService)
    }
 
    static createMethodsOnTenantClass(ApplicationContext ctx) {
        Class tenantClass = ConfigurationHolder.config.multitenant?.tenantClass ?: null
        
        if (tenantClass != null) {
            createWithThisTenantMethod(tenantClass, ctx.multiTenantService)
            createWithTenantIdMethod(tenantClass, ctx.multiTenantService)
            createWithoutTenantRestrictionMethod(tenantClass, ctx.multiTenantService)
        } else {
            log.warn "Can't add tenant methods, no tenant class configured"
        }
    }
    
    static createWithThisTenantMethod(Class tenantClass, MultiTenantService mtService) {
        tenantClass.metaClass.withThisTenant = { Closure closure ->
            Integer tenantId = tenantId()
            if (tenantId == null) {
                String exMessage = ("Can't execute closure in tenent namespace without a tenant id. "
                    + "Make sure that the domain instance has been saved to database "
                    + "(if you're using Hibernate and primary key as tenant id)")
                    
                throw new TenantException(exMessage)
            } else {
                mtService.doWithTenantId(tenantId, closure)
            }
        }
    }
    
    static createWithTenantIdMethod(Class tenantClass, MultiTenantService mtService) {
        tenantClass.metaClass.'static'.withTenantId = { Integer tenantId, Closure closure ->
            mtService.doWithTenantId(tenantId, closure)
        }
    }
    
    static createWithoutTenantRestrictionMethod(Class tenantClass, MultiTenantService mtService) {
        tenantClass.metaClass.'static'.withoutTenantRestriction = { Closure closure ->
            mtService.doWithTenantId(null, closure)
        }
    }
       
    
    static doWithWebDescriptor = { xml ->
        def contextParam = xml.'context-param'
        contextParam[contextParam.size() - 1] + {
            'filter' {
                'filter-name'('tenantFilter')
                'filter-class'(CurrentTenantServletFilter.name)
            }
        }

        def filter = xml.'filter'
        filter[filter.size() - 1] + {
            'filter-mapping' {
                'filter-name'('tenantFilter')
                'url-pattern'('/*')
                'dispatcher' 'REQUEST'
                'dispatcher' 'ERROR'
            }
        }
    }
    
}
