/*
 * Copyright (C) 2005-2010 Alfresco Software Limited.
 *
 * This file is part of Alfresco
 *
 * Alfresco is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Alfresco is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Alfresco. If not, see <http://www.gnu.org/licenses/>.
 */
package org.alfresco.repo.security.sync.ldap;

import java.text.DateFormat;
import java.text.MessageFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.AbstractCollection;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import javax.naming.CompositeName;
import javax.naming.InvalidNameException;
import javax.naming.Name;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.LdapName;

import org.alfresco.error.AlfrescoRuntimeException;
import org.alfresco.model.ContentModel;
import org.alfresco.repo.management.subsystems.ActivateableBean;
import org.alfresco.repo.security.authentication.AuthenticationException;
import org.alfresco.repo.security.authentication.ldap.LDAPInitialDirContextFactory;
import org.alfresco.repo.security.sync.NodeDescription;
import org.alfresco.repo.security.sync.UserRegistry;
import org.alfresco.service.namespace.NamespaceService;
import org.alfresco.service.namespace.QName;
import org.alfresco.util.PropertyMap;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.factory.InitializingBean;

/**
 * A {@link UserRegistry} implementation with the ability to query Alfresco-like descriptions of users and groups from
 * an LDAP directory, optionally restricted to those modified since a certain time.
 * 
 * @author dward
 */
public class LDAPUserRegistry implements UserRegistry, LDAPNameResolver, InitializingBean, ActivateableBean
{

    /** The logger. */
    private static Log logger = LogFactory.getLog(LDAPUserRegistry.class);

    /** The regular expression that will match the attribute at the end of a range. */
    private static final Pattern PATTERN_RANGE_END = Pattern.compile(";range=[0-9]+-\\*");

    /** Is this bean active? I.e. should this part of the subsystem be used? */
    private boolean active = true;

    /** Enable progress estimation? When enabled, the user query has to be run twice in order to count entries. */
    private boolean enableProgressEstimation = true;

    /** The group query. */
    private String groupQuery = "(objectclass=groupOfNames)";

    /** The group differential query. */
    private String groupDifferentialQuery = "(&(objectclass=groupOfNames)(!(modifyTimestamp<={0})))";

    /** The person query. */
    private String personQuery = "(objectclass=inetOrgPerson)";

    /** The person differential query. */
    private String personDifferentialQuery = "(&(objectclass=inetOrgPerson)(!(modifyTimestamp<={0})))";

    /** The group search base. */
    private String groupSearchBase;

    /** The user search base. */
    private String userSearchBase;

    /** The group id attribute name. */
    private String groupIdAttributeName = "cn";

    /** The user id attribute name. */
    private String userIdAttributeName = "uid";

    /** The member attribute name. */
    private String memberAttributeName = "member";

    /** The modification timestamp attribute name. */
    private String modifyTimestampAttributeName = "modifyTimestamp";

    /** The group type. */
    private String groupType = "groupOfNames";

    /** The person type. */
    private String personType = "inetOrgPerson";

    /** The ldap initial context factory. */
    private LDAPInitialDirContextFactory ldapInitialContextFactory;

    /** The namespace service. */
    private NamespaceService namespaceService;

    /** The person attribute mapping. */
    private Map<String, String> personAttributeMapping;

    /** The person attribute defaults. */
    private Map<String, String> personAttributeDefaults = Collections.emptyMap();

    /** The group attribute mapping. */
    private Map<String, String> groupAttributeMapping;

    /** The group attribute defaults. */
    private Map<String, String> groupAttributeDefaults = Collections.emptyMap();

    /**
     * The query batch size. If positive, indicates that RFC 2696 paged results should be used to split query results
     * into batches of the specified size. Overcomes any size limits imposed by the LDAP server.
     */
    private int queryBatchSize;

    /**
     * The attribute retrieval batch size. If positive, indicates that range retrieval should be used to fetch
     * multi-valued attributes (such as member) in batches of the specified size. Overcomes any size limits imposed by
     * the LDAP server.
     */
    private int attributeBatchSize;

    /** Should we error on missing group members?. */
    private boolean errorOnMissingMembers;

    /** Should we error on duplicate group IDs?. */
    private boolean errorOnDuplicateGID;

    /** Should we error on missing group IDs?. */
    private boolean errorOnMissingGID = false;

    /** Should we error on missing user IDs?. */
    private boolean errorOnMissingUID = false;

    /** An array of all LDAP attributes to be queried from users. */
    private String[] userAttributeNames;

    /** An array of all LDAP attributes to be queried from groups. */
    private String[] groupAttributeNames;

    /** The LDAP generalized time format. */
    private DateFormat timestampFormat;

    /**
     * Instantiates a new lDAP user registry.
     */
    public LDAPUserRegistry()
    {
        // Default to official LDAP generalized time format (unfortunately not used by Active Directory)
        setTimestampFormat("yyyyMMddHHmmss'Z'");
    }

    /**
     * Controls whether this bean is active. I.e. should this part of the subsystem be used?
     * 
     * @param active
     *            <code>true</code> if this bean is active
     */
    public void setActive(boolean active)
    {
        this.active = active;
    }

    /**
     * Controls whether progress estimation is enabled. When enabled, the user query has to be run twice in order to
     * count entries.
     * 
     * @param enableProgressEstimation
     *            <code>true</code> if progress estimation is enabled
     */
    public void setEnableProgressEstimation(boolean enableProgressEstimation)
    {
        this.enableProgressEstimation = enableProgressEstimation;
    }

    /**
     * Sets the group id attribute name.
     * 
     * @param groupIdAttributeName
     *            the group id attribute name
     */
    public void setGroupIdAttributeName(String groupIdAttributeName)
    {
        this.groupIdAttributeName = groupIdAttributeName;
    }

    /**
     * Sets the group query.
     * 
     * @param groupQuery
     *            the group query
     */
    public void setGroupQuery(String groupQuery)
    {
        this.groupQuery = groupQuery;
    }

    /**
     * Sets the group differential query.
     * 
     * @param groupDifferentialQuery
     *            the group differential query
     */
    public void setGroupDifferentialQuery(String groupDifferentialQuery)
    {
        this.groupDifferentialQuery = groupDifferentialQuery;
    }

    /**
     * Sets the person query.
     * 
     * @param personQuery
     *            the person query
     */
    public void setPersonQuery(String personQuery)
    {
        this.personQuery = personQuery;
    }

    /**
     * Sets the person differential query.
     * 
     * @param personDifferentialQuery
     *            the person differential query
     */
    public void setPersonDifferentialQuery(String personDifferentialQuery)
    {
        this.personDifferentialQuery = personDifferentialQuery;
    }

    /**
     * Sets the group type.
     * 
     * @param groupType
     *            the group type
     */
    public void setGroupType(String groupType)
    {
        this.groupType = groupType;
    }

    /**
     * Sets the member attribute name.
     * 
     * @param memberAttribute
     *            the member attribute name
     */
    public void setMemberAttribute(String memberAttribute)
    {
        this.memberAttributeName = memberAttribute;
    }

    /**
     * Sets the person type.
     * 
     * @param personType
     *            the person type
     */
    public void setPersonType(String personType)
    {
        this.personType = personType;
    }

    /**
     * Sets the group search base.
     * 
     * @param groupSearchBase
     *            the group search base
     */
    public void setGroupSearchBase(String groupSearchBase)
    {
        this.groupSearchBase = groupSearchBase;
    }

    /**
     * Sets the user search base.
     * 
     * @param userSearchBase
     *            the user search base
     */
    public void setUserSearchBase(String userSearchBase)
    {
        this.userSearchBase = userSearchBase;
    }

    /**
     * Sets the user id attribute name.
     * 
     * @param userIdAttributeName
     *            the user id attribute name
     */
    public void setUserIdAttributeName(String userIdAttributeName)
    {
        this.userIdAttributeName = userIdAttributeName;
    }

    /**
     * Sets the modification timestamp attribute name.
     * 
     * @param modifyTimestampAttributeName
     *            the modification timestamp attribute name
     */
    public void setModifyTimestampAttributeName(String modifyTimestampAttributeName)
    {
        this.modifyTimestampAttributeName = modifyTimestampAttributeName;
    }

    /**
     * Sets the timestamp format. Unfortunately, this varies between directory servers.
     * 
     * @param timestampFormat
     *            the timestamp format
     *            <ul>
     *            <li>OpenLDAP: "yyyyMMddHHmmss'Z'"
     *            <li>Active Directory: "yyyyMMddHHmmss'.0Z'"
     *            </ul>
     */
    public void setTimestampFormat(String timestampFormat)
    {
        this.timestampFormat = new SimpleDateFormat(timestampFormat, Locale.UK);
        this.timestampFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
    }

    /**
     * Decides whether to error on missing group members.
     * 
     * @param errorOnMissingMembers
     *            <code>true</code> if we should error on missing group members
     */
    public void setErrorOnMissingMembers(boolean errorOnMissingMembers)
    {
        this.errorOnMissingMembers = errorOnMissingMembers;
    }

    /**
     * Decides whether to error on missing group IDs.
     * 
     * @param errorOnMissingGID
     *            <code>true</code> if we should error on missing group IDs
     */
    public void setErrorOnMissingGID(boolean errorOnMissingGID)
    {
        this.errorOnMissingGID = errorOnMissingGID;
    }

    /**
     * Decides whether to error on missing user IDs.
     * 
     * @param errorOnMissingUID
     *            <code>true</code> if we should error on missing user IDs
     */
    public void setErrorOnMissingUID(boolean errorOnMissingUID)
    {
        this.errorOnMissingUID = errorOnMissingUID;
    }

    /**
     * Decides whether to error on duplicate group IDs.
     * 
     * @param errorOnDuplicateGID
     *            <code>true</code> if we should error on duplicate group IDs
     */
    public void setErrorOnDuplicateGID(boolean errorOnDuplicateGID)
    {
        this.errorOnDuplicateGID = errorOnDuplicateGID;
    }

    /**
     * Sets the LDAP initial dir context factory.
     * 
     * @param ldapInitialDirContextFactory
     *            the new LDAP initial dir context factory
     */
    public void setLDAPInitialDirContextFactory(LDAPInitialDirContextFactory ldapInitialDirContextFactory)
    {
        this.ldapInitialContextFactory = ldapInitialDirContextFactory;
    }

    /**
     * Sets the namespace service.
     * 
     * @param namespaceService
     *            the namespace service
     */
    public void setNamespaceService(NamespaceService namespaceService)
    {
        this.namespaceService = namespaceService;
    }

    /**
     * Sets the person attribute defaults.
     * 
     * @param personAttributeDefaults
     *            the person attribute defaults
     */
    public void setPersonAttributeDefaults(Map<String, String> personAttributeDefaults)
    {
        this.personAttributeDefaults = personAttributeDefaults;
    }

    /**
     * Sets the person attribute mapping.
     * 
     * @param personAttributeMapping
     *            the person attribute mapping
     */
    public void setPersonAttributeMapping(Map<String, String> personAttributeMapping)
    {
        this.personAttributeMapping = personAttributeMapping;
    }

    /**
     * Sets the group attribute defaults.
     * 
     * @param groupAttributeDefaults
     *            the group attribute defaults
     */
    public void setGroupAttributeDefaults(Map<String, String> groupAttributeDefaults)
    {
        this.groupAttributeDefaults = groupAttributeDefaults;
    }

    /**
     * Sets the group attribute mapping.
     * 
     * @param groupAttributeMapping
     *            the group attribute mapping
     */
    public void setGroupAttributeMapping(Map<String, String> groupAttributeMapping)
    {
        this.groupAttributeMapping = groupAttributeMapping;
    }

    /**
     * Sets the query batch size.
     * 
     * @param queryBatchSize
     *            If positive, indicates that RFC 2696 paged results should be used to split query results into batches
     *            of the specified size. Overcomes any size limits imposed by the LDAP server.
     */
    public void setQueryBatchSize(int queryBatchSize)
    {
        this.queryBatchSize = queryBatchSize;
    }

    /**
     * Sets the attribute batch size.
     * 
     * @param attributeBatchSize
     *            If positive, indicates that range retrieval should be used to fetch multi-valued attributes (such as
     *            member) in batches of the specified size. Overcomes any size limits imposed by the LDAP server.
     */
    public void setAttributeBatchSize(int attributeBatchSize)
    {
        this.attributeBatchSize = attributeBatchSize;
    }

    /*
     * (non-Javadoc)
     * @see org.alfresco.repo.management.subsystems.ActivateableBean#isActive()
     */
    public boolean isActive()
    {
        return this.active;
    }

    /*
     * (non-Javadoc)
     * @see org.springframework.beans.factory.InitializingBean#afterPropertiesSet()
     */
    public void afterPropertiesSet() throws Exception
    {
        if (this.personAttributeMapping == null)
        {
            this.personAttributeMapping = new HashMap<String, String>(5);
        }
        this.personAttributeMapping.put(ContentModel.PROP_USERNAME.toPrefixString(this.namespaceService),
                this.userIdAttributeName);
        this.userAttributeNames = getAttributeNames(this.personAttributeMapping);

        // Include a range restriction for the multi-valued member attribute if this is enabled
        if (this.groupAttributeMapping == null)
        {
            this.groupAttributeMapping = new HashMap<String, String>(5);
        }
        this.groupAttributeMapping.put(ContentModel.PROP_AUTHORITY_NAME.toPrefixString(this.namespaceService),
                this.groupIdAttributeName);
        this.groupAttributeNames = getAttributeNames(this.groupAttributeMapping,
                this.attributeBatchSize > 0 ? this.memberAttributeName + ";range=0-" + (this.attributeBatchSize - 1)
                        : this.memberAttributeName);
    }

    /*
     * (non-Javadoc)
     * @see org.alfresco.repo.security.sync.UserRegistry#getPersons(java.util.Date)
     */
    public Collection<NodeDescription> getPersons(Date modifiedSince)
    {
        return new PersonCollection(modifiedSince);
    }

    /*
     * (non-Javadoc)
     * @see org.alfresco.repo.security.sync.UserRegistry#processDeletions(java.util.Set)
     */
    public void processDeletions(final Set<String> candidateAuthoritiesForDeletion)
    {
        processQuery(new SearchCallback()
        {
            public void process(SearchResult result) throws NamingException, ParseException
            {
                Attribute nameAttribute = result.getAttributes().get(LDAPUserRegistry.this.userIdAttributeName);
                if (nameAttribute == null)
                {
                    if (LDAPUserRegistry.this.errorOnMissingUID)
                    {
                        throw new AlfrescoRuntimeException("User missing user id attribute DN ="
                                + result.getNameInNamespace() + "  att = " + LDAPUserRegistry.this.userIdAttributeName);
                    }
                    else
                    {
                        LDAPUserRegistry.logger.warn("User missing user id attribute DN ="
                                + result.getNameInNamespace() + "  att = " + LDAPUserRegistry.this.userIdAttributeName);
                    }
                }
                else
                {
                    String authority = (String) nameAttribute.get();
                    candidateAuthoritiesForDeletion.remove(authority);
                }
            }

            public void close() throws NamingException
            {
            }

        }, this.userSearchBase, this.personQuery, new String[]
        {
            this.userIdAttributeName
        });
        processQuery(new SearchCallback()
        {

            public void process(SearchResult result) throws NamingException, ParseException
            {
                Attribute nameAttribute = result.getAttributes().get(LDAPUserRegistry.this.groupIdAttributeName);
                if (nameAttribute == null)
                {
                    if (LDAPUserRegistry.this.errorOnMissingGID)
                    {
                        throw new AlfrescoRuntimeException(
                                "NodeDescription returned by group search does not have mandatory group id attribute "
                                        + result.getNameInNamespace());
                    }
                    else
                    {
                        LDAPUserRegistry.logger.warn("Missing GID on " + result.getNameInNamespace());
                    }
                }
                else
                {
                    String authority = "GROUP_" + (String) nameAttribute.get();
                    candidateAuthoritiesForDeletion.remove(authority);
                }
            }

            public void close() throws NamingException
            {
            }

        }, this.groupSearchBase, this.groupQuery, new String[]
        {
            this.groupIdAttributeName
        });
    }

    /*
     * (non-Javadoc)
     * @see org.alfresco.repo.security.sync.UserRegistry#getGroups(java.util.Date)
     */
    public Collection<NodeDescription> getGroups(Date modifiedSince)
    {
        // Work out whether the user and group trees are disjoint. This may allow us to optimize reverse DN
        // resolution.
        final LdapName groupDistinguishedNamePrefix;
        final LdapName userDistinguishedNamePrefix;
        try
        {
            groupDistinguishedNamePrefix = new LdapName(this.groupSearchBase.toLowerCase());
            userDistinguishedNamePrefix = new LdapName(this.userSearchBase.toLowerCase());
        }
        catch (InvalidNameException e)
        {
            throw new AlfrescoRuntimeException("User and group import failed", e);
        }
        final boolean disjoint = !groupDistinguishedNamePrefix.startsWith(userDistinguishedNamePrefix)
                && !userDistinguishedNamePrefix.startsWith(groupDistinguishedNamePrefix);

        // Choose / generate the query
        String query;
        if (modifiedSince == null)
        {
            query = this.groupQuery;
        }
        else
        {
            query = MessageFormat.format(this.groupDifferentialQuery, this.timestampFormat.format(modifiedSince));
        }

        // Run the query and process the results
        final Map<String, NodeDescription> lookup = new TreeMap<String, NodeDescription>();
        processQuery(new SearchCallback()
        {
            // We get a whole new context to avoid interference with cookies from paged results
            private DirContext ctx = LDAPUserRegistry.this.ldapInitialContextFactory.getDefaultIntialDirContext();

            public void process(SearchResult result) throws NamingException, ParseException
            {
                Attributes attributes = result.getAttributes();
                Attribute gidAttribute = attributes.get(LDAPUserRegistry.this.groupIdAttributeName);
                if (gidAttribute == null)
                {
                    if (LDAPUserRegistry.this.errorOnMissingGID)
                    {
                        throw new AlfrescoRuntimeException(
                                "NodeDescription returned by group search does not have mandatory group id attribute "
                                        + attributes);
                    }
                    else
                    {
                        LDAPUserRegistry.logger.warn("Missing GID on " + attributes);
                        return;
                    }
                }
                String groupShortName = gidAttribute.get(0).toString();
                String gid = "GROUP_" + groupShortName;

                NodeDescription group = lookup.get(gid);
                if (group == null)
                {
                    // Apply the mapped properties to the node description
                    group = mapToNode(LDAPUserRegistry.this.groupAttributeMapping,
                            LDAPUserRegistry.this.groupAttributeDefaults, result);

                    // Make sure the "GROUP_" prefix is applied
                    group.getProperties().put(ContentModel.PROP_AUTHORITY_NAME, gid);
                    lookup.put(gid, group);
                }
                else if (LDAPUserRegistry.this.errorOnDuplicateGID)
                {
                    throw new AlfrescoRuntimeException("Duplicate group id found for " + gid);
                }
                else
                {
                    LDAPUserRegistry.logger.warn("Duplicate gid found for " + gid + " -> merging definitions");
                }

                Set<String> childAssocs = group.getChildAssociations();

                // Get the repeating (and possibly range restricted) member attribute
                Attribute memAttribute = getRangeRestrictedAttribute(attributes,
                        LDAPUserRegistry.this.memberAttributeName);
                int nextStart = LDAPUserRegistry.this.attributeBatchSize;

                // Loop until we get to the end of the range
                while (memAttribute != null)
                {
                    for (int i = 0; i < memAttribute.size(); i++)
                    {
                        String attribute = (String) memAttribute.get(i);
                        if (attribute != null && attribute.length() > 0)
                        {
                            try
                            {
                                // Attempt to parse the member attribute as a DN. If this fails we have a fallback
                                // in the catch block
                                LdapName distinguishedNameForComparison = new LdapName(attribute.toLowerCase());
                                Attribute nameAttribute;

                                // If the user and group search bases are different we may be able to recognize user
                                // and group DNs without a secondary lookup
                                if (disjoint)
                                {
                                    LdapName distinguishedName = new LdapName(attribute);
                                    Attributes nameAttributes = distinguishedName.getRdn(distinguishedName.size() - 1)
                                            .toAttributes();

                                    // Recognize user DNs
                                    if (distinguishedNameForComparison.startsWith(userDistinguishedNamePrefix)
                                            && (nameAttribute = nameAttributes
                                                    .get(LDAPUserRegistry.this.userIdAttributeName)) != null)
                                    {
                                        childAssocs.add((String) nameAttribute.get());
                                        continue;
                                    }

                                    // Recognize group DNs
                                    if (distinguishedNameForComparison.startsWith(groupDistinguishedNamePrefix)
                                            && (nameAttribute = nameAttributes
                                                    .get(LDAPUserRegistry.this.groupIdAttributeName)) != null)
                                    {
                                        childAssocs.add("GROUP_" + nameAttribute.get());
                                        continue;
                                    }
                                }

                                // If we can't determine the name and type from the DN alone, try a directory lookup
                                if (distinguishedNameForComparison.startsWith(userDistinguishedNamePrefix)
                                        || distinguishedNameForComparison.startsWith(groupDistinguishedNamePrefix))
                                {
                                    try
                                    {
                                        Attributes childAttributes = this.ctx.getAttributes(jndiName(attribute),
                                                new String[]
                                                {
                                                    "objectclass", LDAPUserRegistry.this.groupIdAttributeName,
                                                    LDAPUserRegistry.this.userIdAttributeName
                                                });
                                        Attribute objectClass = childAttributes.get("objectclass");
                                        if (hasAttributeValue(objectClass, LDAPUserRegistry.this.personType))
                                        {
                                            nameAttribute = childAttributes
                                                    .get(LDAPUserRegistry.this.userIdAttributeName);
                                            if (nameAttribute == null)
                                            {
                                                if (LDAPUserRegistry.this.errorOnMissingUID)
                                                {
                                                    throw new AlfrescoRuntimeException(
                                                            "User missing user id attribute DN =" + attribute
                                                                    + "  att = "
                                                                    + LDAPUserRegistry.this.userIdAttributeName);
                                                }
                                                else
                                                {
                                                    LDAPUserRegistry.logger.warn("User missing user id attribute DN ="
                                                            + attribute + "  att = "
                                                            + LDAPUserRegistry.this.userIdAttributeName);
                                                    continue;
                                                }
                                            }

                                            childAssocs.add((String) nameAttribute.get());
                                            continue;
                                        }
                                        else if (hasAttributeValue(objectClass, LDAPUserRegistry.this.groupType))
                                        {
                                            nameAttribute = childAttributes
                                                    .get(LDAPUserRegistry.this.groupIdAttributeName);
                                            if (nameAttribute == null)
                                            {
                                                if (LDAPUserRegistry.this.errorOnMissingGID)
                                                {
                                                    throw new AlfrescoRuntimeException(
                                                            "Group returned by group search does not have mandatory group id attribute "
                                                                    + attributes);
                                                }
                                                else
                                                {
                                                    LDAPUserRegistry.logger.warn("Missing GID on " + childAttributes);
                                                    continue;
                                                }
                                            }
                                            childAssocs.add("GROUP_" + nameAttribute.get());
                                            continue;
                                        }
                                    }
                                    catch (NamingException e)
                                    {
                                        // Unresolvable name
                                        if (LDAPUserRegistry.this.errorOnMissingMembers)
                                        {
                                            throw new AlfrescoRuntimeException("Failed to resolve member of group '"
                                                    + groupShortName + "' with distinguished name: " + attribute, e);
                                        }
                                        LDAPUserRegistry.logger.warn("Failed to resolve member of group '"
                                                + groupShortName + "' with distinguished name: " + attribute, e);
                                        continue;
                                    }
                                }
                                if (LDAPUserRegistry.this.errorOnMissingMembers)
                                {
                                    throw new AlfrescoRuntimeException("Failed to resolve member of group '"
                                            + groupShortName + "' with distinguished name: " + attribute);
                                }
                                LDAPUserRegistry.logger.warn("Failed to resolve member of group '" + groupShortName
                                        + "' with distinguished name: " + attribute);
                            }
                            catch (InvalidNameException e)
                            {
                                // The member attribute didn't parse as a DN. So assume we have a group class like
                                // posixGroup (FDS) that directly lists user names
                                childAssocs.add(attribute);
                            }
                        }
                    }

                    // If we are using attribute matching and we haven't got to the end (indicated by an asterisk),
                    // fetch the next batch
                    if (nextStart > 0
                            && !LDAPUserRegistry.PATTERN_RANGE_END.matcher(memAttribute.getID().toLowerCase()).find())
                    {
                        Attributes childAttributes = this.ctx.getAttributes(jndiName(result.getNameInNamespace()),
                                new String[]
                                {
                                    LDAPUserRegistry.this.memberAttributeName + ";range=" + nextStart + '-'
                                            + (nextStart + LDAPUserRegistry.this.attributeBatchSize - 1)
                                });
                        memAttribute = getRangeRestrictedAttribute(childAttributes,
                                LDAPUserRegistry.this.memberAttributeName);
                        nextStart += LDAPUserRegistry.this.attributeBatchSize;
                    }
                    else
                    {
                        memAttribute = null;
                    }
                }
            }

            public void close() throws NamingException
            {
                this.ctx.close();
            }
        }, this.groupSearchBase, query, this.groupAttributeNames);

        if (LDAPUserRegistry.logger.isDebugEnabled())
        {
            LDAPUserRegistry.logger.debug("Found " + lookup.size());
        }

        return lookup.values();
    }

    /*
     * (non-Javadoc)
     * @see org.alfresco.repo.security.sync.ldap.LDAPNameResolver#resolveDistinguishedName(java.lang.String)
     */
    public String resolveDistinguishedName(String userId) throws AuthenticationException
    {
        SearchControls userSearchCtls = new SearchControls();
        userSearchCtls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        userSearchCtls.setReturningAttributes(new String[] {});
        InitialDirContext ctx = null;
        try
        {
            ctx = this.ldapInitialContextFactory.getDefaultIntialDirContext();

            // Execute the user query with an additional condition that ensures only the user with the required ID is
            // returned
            NamingEnumeration<SearchResult> searchResults = ctx.search(this.userSearchBase, "(&" + this.personQuery
                    + "(" + this.userIdAttributeName + "=" + userId + "))", userSearchCtls);

            if (searchResults.hasMore())
            {
                return searchResults.next().getNameInNamespace();
            }
            throw new AuthenticationException("Failed to resolve user: " + userId);
        }
        catch (NamingException e)
        {
            throw new AlfrescoRuntimeException("Failed to resolve user ID: " + userId, e);
        }
        finally
        {
            if (ctx != null)
            {
                try
                {
                    ctx.close();
                }
                catch (NamingException e)
                {
                }
            }
        }
    }

    private String[] getAttributeNames(Map<String, String> attributeMapping, String... extraAttibutes)
    {
        Set<String> attributeSet = new TreeSet<String>();
        attributeSet.addAll(Arrays.asList(extraAttibutes));
        attributeSet.add(this.modifyTimestampAttributeName);
        for (String attribute : attributeMapping.values())
        {
            if (attribute != null)
            {
                attributeSet.add(attribute);
            }
        }
        String[] attributeNames = new String[attributeSet.size()];
        attributeSet.toArray(attributeNames);
        return attributeNames;
    }

    private NodeDescription mapToNode(Map<String, String> attributeMapping, Map<String, String> attributeDefaults,
            SearchResult result) throws NamingException
    {
        NodeDescription nodeDescription = new NodeDescription(result.getNameInNamespace());
        Attributes ldapAttributes = result.getAttributes();

        // Parse the timestamp
        Attribute modifyTimestamp = ldapAttributes.get(this.modifyTimestampAttributeName);
        if (modifyTimestamp != null)
        {
            try
            {
                nodeDescription.setLastModified(this.timestampFormat.parse(modifyTimestamp.get().toString()));
            }
            catch (ParseException e)
            {
                throw new AlfrescoRuntimeException("Failed to parse timestamp.", e);
            }
        }

        // Apply the mapped attributes
        PropertyMap properties = nodeDescription.getProperties();
        for (String key : attributeMapping.keySet())
        {
            QName keyQName = QName.createQName(key, this.namespaceService);

            // cater for null
            String attributeName = attributeMapping.get(key);
            if (attributeName != null)
            {
                Attribute attribute = ldapAttributes.get(attributeName);
                if (attribute != null)
                {
                    String value = (String) attribute.get(0);
                    if (value != null)
                    {
                        properties.put(keyQName, value);
                    }
                }
                else
                {
                    String defaultValue = attributeDefaults.get(key);
                    if (defaultValue != null)
                    {
                        properties.put(keyQName, defaultValue);
                    }
                }
            }
            else
            {
                String defaultValue = attributeDefaults.get(key);
                if (defaultValue != null)
                {
                    properties.put(keyQName, defaultValue);
                }
            }
        }
        return nodeDescription;
    }

    /**
     * Converts a given DN into one suitable for use through JNDI. In particular, escapes special characters such as '/'
     * which have special meaning to JNDI.
     * 
     * @param dn
     *            the dn
     * @return the name
     * @throws InvalidNameException
     *             the invalid name exception
     */
    private static Name jndiName(String dn) throws InvalidNameException
    {
        Name n = new CompositeName();
        n.add(dn);
        return n;
    }

    /**
     * Invokes the given callback on each entry returned by the given query.
     * 
     * @param callback
     *            the callback
     * @param searchBase
     *            the base DN for the search
     * @param query
     *            the query
     * @param returningAttributes
     *            the attributes to include in search results
     */
    private void processQuery(SearchCallback callback, String searchBase, String query, String[] returningAttributes)
    {
        SearchControls searchControls = new SearchControls();
        searchControls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        searchControls.setReturningAttributes(returningAttributes);

        InitialDirContext ctx = null;
        try
        {
            ctx = this.ldapInitialContextFactory.getDefaultIntialDirContext(this.queryBatchSize);
            do
            {
                NamingEnumeration<SearchResult> searchResults;
                searchResults = ctx.search(searchBase, query, searchControls);

                while (searchResults.hasMore())
                {
                    SearchResult result = searchResults.next();
                    callback.process(result);
                }
            }
            while (this.ldapInitialContextFactory.hasNextPage(ctx, this.queryBatchSize));
        }
        catch (NamingException e)
        {
            throw new AlfrescoRuntimeException("User and group import failed", e);
        }
        catch (ParseException e)
        {
            throw new AlfrescoRuntimeException("User and group import failed", e);
        }
        finally
        {
            if (ctx != null)
            {
                try
                {
                    ctx.close();
                }
                catch (NamingException e)
                {
                }
            }
            try
            {
                callback.close();
            }
            catch (NamingException e)
            {
            }
        }
    }

    /**
     * Does a case-insensitive search for the given value in an attribute.
     * 
     * @param attribute
     *            the attribute
     * @param value
     *            the value to search for
     * @return <code>true</code>, if the value was found
     * @throws NamingException
     *             if there is a problem accessing the attribute values
     */
    private boolean hasAttributeValue(Attribute attribute, String value) throws NamingException
    {
        if (attribute != null)
        {
            NamingEnumeration<?> values = attribute.getAll();
            while (values.hasMore())
            {
                try
                {
                    if (value.equalsIgnoreCase((String) values.next()))
                    {
                        return true;
                    }
                }
                catch (ClassCastException e)
                {
                    // Not a string value. ignore and continue
                }
            }
        }
        return false;
    }

    /**
     * Gets the values of a repeating attribute that may have range restriction options. If an attribute is range
     * restricted, it will appear in the attribute set with a ";range=i-j" option, where i and j indicate the start and
     * end index, and j is '*' if it is at the end.
     * 
     * @param attributes
     *            the attributes
     * @param attributeName
     *            the attribute name
     * @return the range restricted attribute
     * @throws NamingException
     *             the naming exception
     */
    private Attribute getRangeRestrictedAttribute(Attributes attributes, String attributeName) throws NamingException
    {
        Attribute unrestricted = attributes.get(attributeName);
        if (unrestricted != null)
        {
            return unrestricted;
        }
        NamingEnumeration<? extends Attribute> i = attributes.getAll();
        String searchString = attributeName.toLowerCase() + ';';
        while (i.hasMore())
        {
            Attribute attribute = i.next();
            if (attribute.getID().toLowerCase().startsWith(searchString))
            {
                return attribute;
            }
        }
        return null;
    }

    /**
     * Wraps the LDAP user query as a virtual {@link Collection}.
     */
    public class PersonCollection extends AbstractCollection<NodeDescription>
    {

        /** The query. */
        private String query;

        /** The total estimated size. */
        private int totalEstimatedSize;

        /**
         * Instantiates a new person collection.
         * 
         * @param modifiedSince
         *            if non-null, then only descriptions of users modified since this date should be returned; if
         *            <code>null</code> then descriptions of all users should be returned.
         */
        public PersonCollection(Date modifiedSince)
        {
            // Choose / generate the appropriate query
            if (modifiedSince == null)
            {
                this.query = LDAPUserRegistry.this.personQuery;
            }
            else
            {
                this.query = MessageFormat.format(LDAPUserRegistry.this.personDifferentialQuery,
                        LDAPUserRegistry.this.timestampFormat.format(modifiedSince));
            }

            // Estimate the size of this collection by running the entire query once, if progress
            // estimation is enabled
            if (LDAPUserRegistry.this.enableProgressEstimation)
            {
                class CountingCallback implements SearchCallback
                {
                    int count;

                    /*
                     * (non-Javadoc)
                     * @see
                     * org.alfresco.repo.security.sync.ldap.LDAPUserRegistry.SearchCallback#process(javax.naming.directory
                     * .SearchResult)
                     */
                    public void process(SearchResult result) throws NamingException, ParseException
                    {
                        this.count++;
                    }

                    /*
                     * (non-Javadoc)
                     * @see org.alfresco.repo.security.sync.ldap.LDAPUserRegistry.SearchCallback#close()
                     */
                    public void close() throws NamingException
                    {
                    }

                }
                CountingCallback countingCallback = new CountingCallback();
                processQuery(countingCallback, LDAPUserRegistry.this.userSearchBase, this.query, new String[] {});
                this.totalEstimatedSize = countingCallback.count;
            }
            else
            {
                this.totalEstimatedSize = -1;
            }
        }

        /*
         * (non-Javadoc)
         * @see java.util.AbstractCollection#iterator()
         */
        @Override
        public Iterator<NodeDescription> iterator()
        {
            return new PersonIterator();
        }

        /*
         * (non-Javadoc)
         * @see java.util.AbstractCollection#size()
         */
        @Override
        public int size()
        {
            return this.totalEstimatedSize;
        }

        /**
         * An iterator over the person collection. Wraps the LDAP query in 'real time'.
         */
        private class PersonIterator implements Iterator<NodeDescription>
        {

            /** The directory context. */
            private InitialDirContext ctx;

            /** The user search controls. */
            private SearchControls userSearchCtls;

            /** The search results. */
            private NamingEnumeration<SearchResult> searchResults;

            /** The uids. */
            private HashSet<String> uids = new HashSet<String>();

            /** The next node description to return. */
            private NodeDescription next;

            /**
             * Instantiates a new person iterator.
             */
            public PersonIterator()
            {
                try
                {

                    this.ctx = LDAPUserRegistry.this.ldapInitialContextFactory
                            .getDefaultIntialDirContext(LDAPUserRegistry.this.queryBatchSize);

                    this.userSearchCtls = new SearchControls();
                    this.userSearchCtls.setSearchScope(SearchControls.SUBTREE_SCOPE);
                    this.userSearchCtls.setReturningAttributes(LDAPUserRegistry.this.userAttributeNames);

                    this.next = fetchNext();
                }
                catch (NamingException e)
                {
                    throw new AlfrescoRuntimeException("Failed to import people.", e);
                }
                finally
                {
                    if (this.searchResults == null)
                    {
                        try
                        {
                            this.ctx.close();
                        }
                        catch (Exception e)
                        {
                        }
                        this.ctx = null;
                    }
                }
            }

            /*
             * (non-Javadoc)
             * @see java.util.Iterator#hasNext()
             */
            public boolean hasNext()
            {
                return this.next != null;
            }

            /*
             * (non-Javadoc)
             * @see java.util.Iterator#next()
             */
            public NodeDescription next()
            {
                if (this.next == null)
                {
                    throw new IllegalStateException();
                }
                NodeDescription current = this.next;
                try
                {
                    this.next = fetchNext();
                }
                catch (NamingException e)
                {
                    throw new AlfrescoRuntimeException("Failed to import people.", e);
                }
                return current;
            }

            /**
             * Pre-fetches the next node description to be returned.
             * 
             * @return the node description
             * @throws NamingException
             *             on a naming exception
             */
            private NodeDescription fetchNext() throws NamingException
            {
                boolean readyForNextPage;
                do
                {
                    readyForNextPage = this.searchResults == null;
                    while (!readyForNextPage && this.searchResults.hasMore())
                    {
                        SearchResult result = this.searchResults.next();
                        Attributes attributes = result.getAttributes();
                        Attribute uidAttribute = attributes.get(LDAPUserRegistry.this.userIdAttributeName);
                        if (uidAttribute == null)
                        {
                            if (LDAPUserRegistry.this.errorOnMissingUID)
                            {
                                throw new AlfrescoRuntimeException(
                                        "User returned by user search does not have mandatory user id attribute "
                                                + attributes);
                            }
                            else
                            {
                                LDAPUserRegistry.logger
                                        .warn("User returned by user search does not have mandatory user id attribute "
                                                + attributes);
                                continue;
                            }
                        }
                        String uid = (String) uidAttribute.get(0);

                        if (this.uids.contains(uid))
                        {
                            LDAPUserRegistry.logger
                                    .warn("Duplicate uid found - there will be more than one person object for this user - "
                                            + uid);
                        }

                        this.uids.add(uid);

                        if (LDAPUserRegistry.logger.isDebugEnabled())
                        {
                            LDAPUserRegistry.logger.debug("Adding user for " + uid);
                        }

                        // Apply the mapped properties to the node description
                        return mapToNode(LDAPUserRegistry.this.personAttributeMapping,
                                LDAPUserRegistry.this.personAttributeDefaults, result);
                    }

                    // Examine the paged results control response for an indication that another page is available
                    if (!readyForNextPage)
                    {
                        readyForNextPage = LDAPUserRegistry.this.ldapInitialContextFactory.hasNextPage(this.ctx,
                                LDAPUserRegistry.this.queryBatchSize);
                    }

                    // Fetch the next page if there is one
                    if (readyForNextPage)
                    {
                        this.searchResults = this.ctx.search(LDAPUserRegistry.this.userSearchBase,
                                PersonCollection.this.query, this.userSearchCtls);
                    }
                }
                while (readyForNextPage);
                this.searchResults.close();
                this.searchResults = null;
                this.ctx.close();
                this.ctx = null;
                return null;
            }

            /*
             * (non-Javadoc)
             * @see java.util.Iterator#remove()
             */
            public void remove()
            {
                throw new UnsupportedOperationException();
            }
        };
    }

    /**
     * An interface for callbacks passed to the
     * {@link LDAPUserRegistry#processQuery(SearchCallback, String, String, String[])} method.
     */
    protected static interface SearchCallback
    {

        /**
         * Processes the given search result.
         * 
         * @param result
         *            the result
         * @throws NamingException
         *             on naming exceptions
         * @throws ParseException
         *             on parse exceptions
         */
        public void process(SearchResult result) throws NamingException, ParseException;

        /**
         * Release any resources held by the callback.
         * 
         * @throws NamingException
         *             the naming exception
         */
        public void close() throws NamingException;
    }

}
