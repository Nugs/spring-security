/*
 * Copyright 2002-2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.security.config.http

import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContext
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.core.session.SessionRegistryImpl
import org.springframework.security.util.FieldUtils
import org.springframework.security.web.authentication.RememberMeServices
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler
import org.springframework.security.web.authentication.logout.LogoutFilter
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler
import org.springframework.security.web.authentication.rememberme.RememberMeAuthenticationFilter
import org.springframework.security.web.authentication.session.SessionFixationProtectionStrategy
import org.springframework.security.web.context.NullSecurityContextRepository
import org.springframework.security.web.context.SaveContextOnUpdateOrErrorResponseWrapper
import org.springframework.security.web.context.SecurityContextPersistenceFilter
import org.springframework.security.web.savedrequest.RequestCacheAwareFilter
import org.springframework.security.web.session.ConcurrentSessionFilter
import org.springframework.security.web.session.SessionManagementFilter
import static org.junit.Assert.assertSame

/**
 * Tests session-related functionality for the &lt;http&gt; namespace element and &lt;session-management&gt;
 *
 * @author Luke Taylor
 * @author Rob Winch
 */
class SessionManagementConfigTests extends AbstractHttpConfigTests {

    def settingCreateSessionToAlwaysSetsFilterPropertiesCorrectly() {
        httpCreateSession('always') { }
        createAppContext();

        def filter = getFilter(SecurityContextPersistenceFilter.class);

        expect:
        filter.forceEagerSessionCreation
        filter.repo.allowSessionCreation
        !filter.repo.disableUrlRewriting
    }

    def settingCreateSessionToNeverSetsFilterPropertiesCorrectly() {
        httpCreateSession('never') { }
        createAppContext();

        def filter = getFilter(SecurityContextPersistenceFilter.class);

        expect:
        !filter.forceEagerSessionCreation
        !filter.repo.allowSessionCreation
    }

    def settingCreateSessionToStatelessSetsFilterPropertiesCorrectly() {
        httpCreateSession('stateless') { }
        createAppContext();

        def filter = getFilter(SecurityContextPersistenceFilter.class);

        expect:
        !filter.forceEagerSessionCreation
        filter.repo instanceof NullSecurityContextRepository
        getFilter(SessionManagementFilter.class) == null
        getFilter(RequestCacheAwareFilter.class) == null
    }

    def settingCreateSessionToIfRequiredDoesntCreateASessionForPublicInvocation() {
        httpCreateSession('ifRequired') { }
        createAppContext();

        def filter = getFilter(SecurityContextPersistenceFilter.class);

        expect:
        !filter.forceEagerSessionCreation
        filter.repo.allowSessionCreation
    }

    def httpCreateSession(String create, Closure c) {
        xml.http(['auto-config': 'true', 'create-session': create], c)
    }

    def concurrentSessionSupportAddsFilterAndExpectedBeans() {
        when:
        httpAutoConfig {
            'session-management'() {
                'concurrency-control'('session-registry-alias':'sr', 'expired-url': '/expired')
            }
        }
        createAppContext();
        List filters = getFilters("/someurl");
        def concurrentSessionFilter = filters.get(0)

        then:
        concurrentSessionFilter instanceof ConcurrentSessionFilter
        concurrentSessionFilter.expiredUrl == '/expired'
        appContext.getBean("sr") != null
        getFilter(SessionManagementFilter.class) != null
        sessionRegistryIsValid();

        concurrentSessionFilter.handlers.size() == 1
        def logoutHandler = concurrentSessionFilter.handlers[0]
        logoutHandler instanceof SecurityContextLogoutHandler
        logoutHandler.invalidateHttpSession

    }

    def 'concurrency-control adds custom logout handlers'() {
        when: 'Custom logout and remember-me'
        httpAutoConfig {
            'session-management'() {
                'concurrency-control'()
            }
            'logout'('invalidate-session': false, 'delete-cookies': 'testCookie')
            'remember-me'()
        }
        createAppContext();

        List filters = getFilters("/someurl")
        ConcurrentSessionFilter concurrentSessionFilter = filters.get(0)
        def logoutHandlers = concurrentSessionFilter.handlers

        then: 'ConcurrentSessionFilter contains the customized LogoutHandlers'
        logoutHandlers.size() == 3
        def securityCtxlogoutHandler = logoutHandlers.find { it instanceof SecurityContextLogoutHandler }
        securityCtxlogoutHandler.invalidateHttpSession == false
        def cookieClearingLogoutHandler = logoutHandlers.find { it instanceof CookieClearingLogoutHandler }
        cookieClearingLogoutHandler.cookiesToClear == ['testCookie']
        def remembermeLogoutHandler = logoutHandlers.find { it instanceof RememberMeServices }
        remembermeLogoutHandler == getFilter(RememberMeAuthenticationFilter.class).rememberMeServices
    }

    def 'concurrency-control with remember-me and no LogoutFilter contains SecurityContextLogoutHandler and RememberMeServices as LogoutHandlers'() {
        when: 'RememberMe and No LogoutFilter'
        xml.http(['entry-point-ref': 'entryPoint'], {
            'session-management'() {
                'concurrency-control'()
            }
            'remember-me'()
        })
        bean('entryPoint', 'org.springframework.security.web.authentication.Http403ForbiddenEntryPoint')
        createAppContext()

        List filters = getFilters("/someurl")
        ConcurrentSessionFilter concurrentSessionFilter = filters.get(0)
        def logoutHandlers = concurrentSessionFilter.handlers

        then: 'SecurityContextLogoutHandler and RememberMeServices are in ConcurrentSessionFilter logoutHandlers'
        !filters.find { it instanceof LogoutFilter }
        logoutHandlers.size() == 2
        def securityCtxlogoutHandler = logoutHandlers.find { it instanceof SecurityContextLogoutHandler }
        securityCtxlogoutHandler.invalidateHttpSession == true
        logoutHandlers.find { it instanceof RememberMeServices } == getFilter(RememberMeAuthenticationFilter).rememberMeServices
    }

    def 'concurrency-control with no remember-me or LogoutFilter contains SecurityContextLogoutHandler as LogoutHandlers'() {
        when: 'No Logout Filter or RememberMe'
        xml.http(['entry-point-ref': 'entryPoint'], {
            'session-management'() {
                'concurrency-control'()
            }
        })
        bean('entryPoint', 'org.springframework.security.web.authentication.Http403ForbiddenEntryPoint')
        createAppContext()

        List filters = getFilters("/someurl")
        ConcurrentSessionFilter concurrentSessionFilter = filters.get(0)
        def logoutHandlers = concurrentSessionFilter.handlers

        then: 'Only SecurityContextLogoutHandler is found in ConcurrentSessionFilter logoutHandlers'
        !filters.find { it instanceof LogoutFilter }
        logoutHandlers.size() == 1
        def securityCtxlogoutHandler = logoutHandlers.find { it instanceof SecurityContextLogoutHandler }
        securityCtxlogoutHandler.invalidateHttpSession == true
    }

    def 'concurrency-control handles default expired-url as null'() {
        httpAutoConfig {
            'session-management'() {
                'concurrency-control'('session-registry-alias':'sr')
            }
        }
        createAppContext();
        List filters = getFilters("/someurl");

        expect:
        filters.get(0).expiredUrl == null
    }

    def externalSessionStrategyIsSupported() {
        when:
        httpAutoConfig {
            'session-management'('session-authentication-strategy-ref':'ss')
        }
        bean('ss', SessionFixationProtectionStrategy.class.name)
        createAppContext();

        then:
        notThrown(Exception.class)
    }

    def externalSessionRegistryBeanIsConfiguredCorrectly() {
        httpAutoConfig {
            'session-management'() {
                'concurrency-control'('session-registry-ref':'sr')
            }
        }
        bean('sr', SessionRegistryImpl.class.name)
        createAppContext();

        expect:
        sessionRegistryIsValid();
    }

    def sessionRegistryIsValid() {
        Object sessionRegistry = appContext.getBean("sr");
        Object sessionRegistryFromConcurrencyFilter = FieldUtils.getFieldValue(
                getFilter(ConcurrentSessionFilter.class), "sessionRegistry");
        Object sessionRegistryFromFormLoginFilter = FieldUtils.getFieldValue(
                getFilter(UsernamePasswordAuthenticationFilter.class),"sessionStrategy.sessionRegistry");
        Object sessionRegistryFromMgmtFilter = FieldUtils.getFieldValue(
                getFilter(SessionManagementFilter.class),"sessionAuthenticationStrategy.sessionRegistry");

        assertSame(sessionRegistry, sessionRegistryFromConcurrencyFilter);
        assertSame(sessionRegistry, sessionRegistryFromMgmtFilter);
        // SEC-1143
        assertSame(sessionRegistry, sessionRegistryFromFormLoginFilter);
        true;
    }

    def concurrentSessionMaxSessionsIsCorrectlyConfigured() {
        setup:
        httpAutoConfig {
            'session-management'('session-authentication-error-url':'/max-exceeded') {
                'concurrency-control'('max-sessions': '2', 'error-if-maximum-exceeded':'true')
            }
        }
        createAppContext();

        def seshFilter = getFilter(SessionManagementFilter.class);
        def auth = new UsernamePasswordAuthenticationToken("bob", "pass");
        SecurityContextHolder.getContext().setAuthentication(auth);
        MockHttpServletResponse mockResponse = new MockHttpServletResponse();
        def response = new SaveContextOnUpdateOrErrorResponseWrapper(mockResponse, false) {
            protected void saveContext(SecurityContext context) {
            }
        };
        when: "First session is established"
        seshFilter.doFilter(new MockHttpServletRequest(), response, new MockFilterChain());
        then: "ok"
        mockResponse.redirectedUrl == null
        when: "Second session is established"
        seshFilter.doFilter(new MockHttpServletRequest(), response, new MockFilterChain());
        then: "ok"
        mockResponse.redirectedUrl == null
        when: "Third session is established"
        seshFilter.doFilter(new MockHttpServletRequest(), response, new MockFilterChain());
        then: "Rejected"
        mockResponse.redirectedUrl == "/max-exceeded";
    }

    def disablingSessionProtectionRemovesSessionManagementFilterIfNoInvalidSessionUrlSet() {
        httpAutoConfig {
            'session-management'('session-fixation-protection': 'none')
        }
        createAppContext()

        expect:
        !(getFilters("/someurl")[8] instanceof SessionManagementFilter)
    }

    def disablingSessionProtectionRetainsSessionManagementFilterInvalidSessionUrlSet() {
        httpAutoConfig {
            'session-management'('session-fixation-protection': 'none', 'invalid-session-url': '/timeoutUrl')
        }
        createAppContext()
        def filter = getFilters("/someurl")[8]

        expect:
        filter instanceof SessionManagementFilter
        filter.invalidSessionStrategy.destinationUrl == '/timeoutUrl'
    }

}
