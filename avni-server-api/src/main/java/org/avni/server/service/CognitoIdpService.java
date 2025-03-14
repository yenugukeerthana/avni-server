package org.avni.server.service;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.cognitoidp.AWSCognitoIdentityProvider;
import com.amazonaws.services.cognitoidp.AWSCognitoIdentityProviderClientBuilder;
import com.amazonaws.services.cognitoidp.model.*;
import org.avni.server.domain.OrganisationConfig;
import org.avni.server.domain.User;
import org.avni.server.framework.context.SpringProfiles;
import org.avni.server.util.S;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.Optional;

import static org.avni.server.application.OrganisationConfigSettingKey.donotRequirePasswordChangeOnFirstLogin;

@Service("CognitoIdpService")
@ConditionalOnExpression("'${avni.idp.type}'=='cognito' or '${avni.idp.type}'=='both'")
public class CognitoIdpService extends IdpServiceImpl {
    private static final Logger logger = LoggerFactory.getLogger(CognitoIdpService.class);

    @Value("${aws.accessKeyId}")
    private String accessKeyId;

    @Value("${aws.secretAccessKey}")
    private String secretAccessKey;

    @Value("${cognito.poolid}")
    private String userPoolId;

    private Regions REGION = Regions.AP_SOUTH_1;

    private AWSCognitoIdentityProvider cognitoClient;

    @Autowired
    public CognitoIdpService(SpringProfiles springProfiles) {
        super(springProfiles);
    }

    @PostConstruct
    public void init() {
        cognitoClient = AWSCognitoIdentityProviderClientBuilder.standard()
                .withCredentials(getCredentialsProvider())
                .withRegion(REGION)
                .build();
        logger.info("Initialized CognitoIDP client");
    }

    @Override
    public UserCreateStatus createUser(User user, OrganisationConfig organisationConfig) {
        AdminCreateUserRequest createUserRequest = prepareCreateUserRequest(user, getDefaultPassword(user));
        return createCognitoUser(createUserRequest, user, organisationConfig.getConfigValueOptional(donotRequirePasswordChangeOnFirstLogin));
    }

    @Override
    public UserCreateStatus createUserWithPassword(User user, String password, OrganisationConfig organisationConfig) {
        return createUserWithPassword(user, password, organisationConfig.getConfigValueOptional(donotRequirePasswordChangeOnFirstLogin));
    }

    private UserCreateStatus createUserWithPassword(User user, String password, Optional<Object> doNotRequirePasswordChangeOnFirstLogin) {
        boolean isTmpPassword = S.isEmpty(password);
        AdminCreateUserRequest createUserRequest = prepareCreateUserRequest(user, isTmpPassword ? getDefaultPassword(user) : password);
        UserCreateStatus userCreateStatus = createCognitoUser(createUserRequest, user, doNotRequirePasswordChangeOnFirstLogin);
        if (!isTmpPassword) {
            boolean passwordResetDone = resetPassword(user, password);
            userCreateStatus.setNonDefaultPasswordSet(passwordResetDone);
        }
        return userCreateStatus;
    }

    @Override
    public UserCreateStatus createSuperAdminWithPassword(User user, String password) {
        return this.createUserWithPassword(user, password, Optional.of(true));
    }

    @Override
    public void updateUser(User user) {
        AdminUpdateUserAttributesRequest updateUserRequest = prepareUpdateUserRequest(user);
        logger.info(String.format("Initiating UPDATE cognito-user request | username '%s' | uuid '%s'", user.getUsername(), user.getUuid()));
        cognitoClient.adminUpdateUserAttributes(updateUserRequest);
        logger.info(String.format("Updated cognito-user | username '%s'", user.getUsername()));
    }

    @Override
    public void disableUser(User user) {
        logger.info(String.format("Initiating DISABLE cognito-user request | username '%s' | uuid '%s'", user.getUsername(), user.getUuid()));
        cognitoClient.adminDisableUser(new AdminDisableUserRequest().withUserPoolId(userPoolId).withUsername(user.getUsername()));
        logger.info(String.format("Disabled cognito-user | username '%s'", user.getUsername()));
    }

    @Override
    public void deleteUser(User user) {
        logger.info(String.format("Initiating DELETE cognito-user request | username '%s' | uuid '%s'", user.getUsername(), user.getUuid()));
        cognitoClient.adminDeleteUser(new AdminDeleteUserRequest().withUserPoolId(userPoolId).withUsername(user.getUsername()));
        logger.info(String.format("Deleted cognito-user | username '%s'", user.getUsername()));
    }

    @Override
    public void enableUser(User user) {
        logger.info(String.format("Initiating ENABLE cognito-user request | username '%s' | uuid '%s'", user.getUsername(), user.getUuid()));
        cognitoClient.adminEnableUser(new AdminEnableUserRequest().withUserPoolId(userPoolId).withUsername(user.getUsername()));
        logger.info(String.format("Enabled cognito-user | username '%s'", user.getUsername()));
    }

    @Override
    public boolean resetPassword(User user, String password) {
        logger.info(String.format("Initiating reset password cognito-user request | username '%s' | uuid '%s'", user.getUsername(), user.getUuid()));
        AdminSetUserPasswordRequest adminSetUserPasswordRequest = new AdminSetUserPasswordRequest()
                .withUserPoolId(userPoolId)
                .withUsername(user.getUsername())
                .withPassword(password)
                .withPermanent(true);
        cognitoClient.adminSetUserPassword(adminSetUserPasswordRequest);
        logger.info(String.format("password reset for cognito-user | username '%s'", user.getUsername()));
        return true;
    }

    @Override
    public boolean exists(User user) {
        try {
            cognitoClient.adminGetUser(new AdminGetUserRequest().withUserPoolId(userPoolId).withUsername(user.getUsername()));
            return true;
        } catch (UserNotFoundException e) {
            return false;
        }
    }

    @Override
    public long getLastLoginTime(User user) {
        return -1L;
    }

    private AWSStaticCredentialsProvider getCredentialsProvider() {
        return new AWSStaticCredentialsProvider(new BasicAWSCredentials(accessKeyId, secretAccessKey));
    }

    private AdminUpdateUserAttributesRequest prepareUpdateUserRequest(User user) {
        return new AdminUpdateUserAttributesRequest()
                .withUserPoolId(userPoolId)
                .withUsername(user.getUsername())
                .withUserAttributes(
                        new AttributeType().withName("email").withValue(user.getEmail()),
                        new AttributeType().withName("phone_number").withValue(user.getPhoneNumber()),
                        new AttributeType().withName("custom:userUUID").withValue(user.getUuid())
                );
    }

    private AdminCreateUserRequest prepareCreateUserRequest(User user, String password) {
        return new AdminCreateUserRequest()
                .withUserPoolId(userPoolId)
                .withUsername(user.getUsername())
                .withUserAttributes(
                        new AttributeType().withName("email").withValue(user.getEmail()),
                        new AttributeType().withName("phone_number").withValue(user.getPhoneNumber()),
                        new AttributeType().withName("email_verified").withValue("true"),
                        new AttributeType().withName("phone_number_verified").withValue("true"),
                        new AttributeType().withName("custom:userUUID").withValue(user.getUuid())
                )
                .withTemporaryPassword(password);
    }

    private UserCreateStatus createCognitoUser(AdminCreateUserRequest createUserRequest, User user, Optional<Object> doNotRequirePasswordChangeOnFirstLogin) {
        logger.info(String.format("Initiating CREATE cognito-user request | username '%s' | uuid '%s'", user.getUsername(), user.getUuid()));
        UserCreateStatus userCreateStatus = new UserCreateStatus();
        AdminCreateUserResult createUserResult;
        try {
            createUserResult = cognitoClient.adminCreateUser(createUserRequest);
            userCreateStatus.setIdpUserCreated(true);
            logger.info(String.format("Created cognito-user | username '%s' | '%s'", user.getUsername(), createUserResult.toString()));
        } catch (UsernameExistsException usernameExistsException) {
            logger.warn("Username: {} exists in Cognito", createUserRequest.getUsername());
            userCreateStatus.setIdpUserCreated(false);
        }

        if (doNotRequirePasswordChangeOnFirstLogin.isPresent() && doNotRequirePasswordChangeOnFirstLogin.get().equals(true)) {
            AdminSetUserPasswordRequest updateUserRequest = new AdminSetUserPasswordRequest()
                    .withUserPoolId(userPoolId)
                    .withUsername(user.getUsername())
                    .withPassword(getDefaultPassword(user))
                    .withPermanent(true);
            try {
                cognitoClient.adminSetUserPassword(updateUserRequest);
                userCreateStatus.setDefaultPasswordPermanent(true);
            } catch (Exception e) {
                logger.warn(String.format("Username: %s exists in Cognito", createUserRequest.getUsername()), e);
                userCreateStatus.setDefaultPasswordPermanent(false);
            }
        }
        return userCreateStatus;
    }
}
