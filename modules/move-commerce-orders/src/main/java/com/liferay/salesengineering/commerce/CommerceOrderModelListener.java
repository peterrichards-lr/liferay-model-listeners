package com.liferay.salesengineering.commerce;

import com.liferay.account.constants.AccountConstants;
import com.liferay.account.model.AccountEntry;
import com.liferay.account.service.AccountEntryLocalService;
import com.liferay.commerce.account.constants.CommerceAccountConstants;
import com.liferay.commerce.model.CommerceOrder;
import com.liferay.portal.kernel.exception.ModelListenerException;
import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.model.BaseModelListener;
import com.liferay.portal.kernel.model.ModelListener;
import com.liferay.portal.kernel.model.User;
import com.liferay.portal.kernel.search.Indexer;
import com.liferay.portal.kernel.search.IndexerRegistryUtil;
import com.liferay.portal.kernel.search.SearchException;
import com.liferay.portal.kernel.security.auth.PrincipalThreadLocal;
import com.liferay.portal.kernel.service.ServiceContext;
import com.liferay.portal.kernel.service.UserLocalService;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * @author peterrichards
 */
@Component(
        immediate = true,
        service = ModelListener.class
)
public class CommerceOrderModelListener extends BaseModelListener<CommerceOrder> {
    private static final Logger _log = LoggerFactory.getLogger(CommerceOrderModelListener.class);

    @Reference
    private AccountEntryLocalService accountEntryLocalService;

    @Reference
    private UserLocalService userLocalService;

    public void onBeforeUpdate(CommerceOrder originalOrder, CommerceOrder order) throws ModelListenerException {
        if (_log.isTraceEnabled()) {
            _log.trace("Original order: {}", originalOrder.toXmlString());
            _log.trace("New order: {}", order.toXmlString());
        }

        final long principalUserId = PrincipalThreadLocal.getUserId();
        final User principalUser;
        try {
            principalUser = userLocalService.getUser(principalUserId);
        } catch (PortalException e) {
            _log.error("Unexpected exception when trying to find the principal user", e);
            return;
        }
        if (principalUser.isDefaultUser()) {
            _log.debug("Principal user is the default user");
        }

        if (_log.isTraceEnabled()) {
            _log.trace("Principal userId: {}", principalUser.getUserId());
            _log.trace("Principal screenName: {}", principalUser.getScreenName());
            _log.trace("Principal emailAddress: {}", principalUser.getEmailAddresses());
        }


        final AccountEntry principalUserPersonalAccount = accountEntryLocalService.fetchPersonAccountEntry(principalUserId);
        if (principalUserPersonalAccount == null) {
            _log.debug("Principal user does not have a personal account");
        } else {
            if (principalUserPersonalAccount.getAccountEntryId() != order.getCommerceAccountId()) {
                _log.debug("The order is not linked to the principal user's personal account");
            }
        }

        final long orderUserId = order.getUserId();
        final User orderUser;
        try {
            orderUser = userLocalService.getUser(orderUserId);
        } catch (PortalException e) {
            _log.error("Unexpected exception when trying to find the order user", e);
            return;
        }
        if (orderUser.isDefaultUser()) {
            _log.debug("Order user is the default user");
        }

        if (_log.isTraceEnabled()) {
            _log.trace("Order userId: {}", orderUser.getUserId());
            _log.trace("Order screenName: {}", orderUser.getScreenName());
            _log.trace("Order emailAddress: {}", orderUser.getEmailAddresses());
        }

        if (principalUserId == orderUserId) {
            if (orderUser.isDefaultUser()) {
                _log.debug("The principal user and order user are the same and are the default user, so skipping the order {}", order.getCommerceOrderId());
                return;
            }
            _log.debug("The principal user and order user are the same but NOT the default user, so processing the order {}", order.getCommerceOrderId());
        } else {
            if (orderUser.isDefaultUser()) {
                _log.debug("The principal user and order user are NOT the same and the order user is the default user, so processing the order {}", order.getCommerceOrderId());
             } else {
                _log.debug("The principal user and order user are NOT the same and the order user is NOT the default user, so processing the order {}", order.getCommerceOrderId());
            }
        }

        final AccountEntry orderUserPersonalAccount = accountEntryLocalService.fetchPersonAccountEntry(orderUserId);
        if (orderUserPersonalAccount == null) {
            _log.debug("User does NOT have a personal account");
        } else {
            if (orderUserPersonalAccount.getAccountEntryId() != order.getCommerceAccountId()) {
                _log.debug("The order is NOT linked to the order user's personal account");
            } else {
                _log.debug("The order is linked to the oder user's personal account");
            }
        }

        try {
            final int accountCount = accountEntryLocalService.getUserAccountEntriesCount(
                    orderUser.getUserId(),
                    AccountConstants.PARENT_ACCOUNT_ENTRY_ID_DEFAULT, null,
                    new String[]{
                            AccountConstants.ACCOUNT_ENTRY_TYPE_BUSINESS,
                            AccountConstants.ACCOUNT_ENTRY_TYPE_PERSON,
                            AccountConstants.ACCOUNT_ENTRY_TYPE_GUEST
                    }
            );
            final List<AccountEntry> accountEntries =
                    accountEntryLocalService.getUserAccountEntries(
                            orderUser.getUserId(),
                            AccountConstants.PARENT_ACCOUNT_ENTRY_ID_DEFAULT, null,
                            new String[]{
                                    AccountConstants.ACCOUNT_ENTRY_TYPE_BUSINESS,
                                    AccountConstants.ACCOUNT_ENTRY_TYPE_PERSON,
                                    AccountConstants.ACCOUNT_ENTRY_TYPE_GUEST
                            },
                            0, accountCount);

            if (_log.isTraceEnabled()) {
                _log.trace("**** all accounts {} ****", accountCount);
                for (AccountEntry accountEntry : accountEntries) {
                    _log.trace("accountEntry: {}", accountEntry);
                }
                _log.trace("*************************");
            }

            final String businessAccountTypeLabel = CommerceAccountConstants.getAccountTypeLabel(CommerceAccountConstants.ACCOUNT_TYPE_BUSINESS);
            final List<AccountEntry> businessAccounts = accountEntries.stream().filter(accountEntry -> accountEntry.getType().equals(businessAccountTypeLabel)).collect(Collectors.toUnmodifiableList());

            if (_log.isTraceEnabled()) {
                _log.trace("**** business accounts {} ****", businessAccounts.size());
                for (AccountEntry accountEntry : businessAccounts) {
                    _log.trace("accountEntry: {}", accountEntry);
                }
                _log.trace("*****************************");
            }

            if (businessAccounts.size() == 1) {
                _log.debug("{} has one business account", orderUser.getUserId());
                final AccountEntry businessAccountEntry = businessAccounts.get(0);
                _log.debug("Setting the commerceAccountId of order {} to {}", order.getCommerceOrderId(), businessAccountEntry.getAccountEntryId());
                order.setCommerceAccountId(businessAccountEntry.getAccountEntryId());
                final long scopeGroupId = order.getScopeGroupId();
                runIndexer(order, getServiceContext(scopeGroupId));

                if (orderUserPersonalAccount != null) {
                    _log.debug("Attempting to delete the user's personal account");
                    final long orderUserPersonalAccountEntryId = orderUserPersonalAccount.getAccountEntryId();
                    accountEntryLocalService.deleteAccountEntry(orderUserPersonalAccountEntryId);
                    runIndexer(orderUserPersonalAccount, getServiceContext(scopeGroupId));
                }
            }
        } catch (PortalException e) {
            _log.error("Unexpected exception", e);
        }
    }

    private ServiceContext getServiceContext(final long groupId) {
        final ServiceContext serviceContext = new ServiceContext();
        serviceContext.setScopeGroupId(groupId);
        return serviceContext;
    }

    public void onBeforeAddAssociation(
            Object classPK, String associationClassName,
            Object associationClassPK)
            throws ModelListenerException {
        _log.debug("classPK: {}, associationClassName: {}, associationClassPK: {} ", classPK, associationClassName, associationClassPK);
    }

    private static <T> void runIndexer(final T entity, final ServiceContext serviceContext) throws SearchException {
        if ((serviceContext == null) || serviceContext.isIndexingEnabled()) {
            _log.debug("Running indexer for {}", entity.getClass().getSimpleName());
            final Indexer<T> indexer = IndexerRegistryUtil.nullSafeGetIndexer(
                    entity.getClass().getName());
            indexer.reindex(entity);
        }
    }
}
