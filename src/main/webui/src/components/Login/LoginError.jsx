import React from 'react';
import {LoginContainer} from './Login';

const LoginError = (props) => {
    return (
        <LoginContainer><div>Login error please retry <a href="/login">login</a></div></LoginContainer>
    );
};

export default LoginError;