import React from 'react';
import styled from 'styled-components';

export const LoginContainer = styled.div`
  display: flex;
  position: fixed;
  width: 100%;
  height: 100%;
  
  align-items: center;
  justify-content: center;
  
  & > form, & > div {
    display: block;
    padding: 30px 50px;
    color: white;
    background-color: #262626;
    text-align: center;
    
    a {
      color: lightblue;
    }
  }
  
  h1 {
    text-align: center;
  }
  
  div {
    margin: 10px 0;
  }
  
  button {
    background-color: #4695EB;
    padding: 10px;
    color: white;
    text-transform: uppercase;
    cursor: pointer;
    font-weight: bold;
    border: none;
  }
`;

const Login = (props) => {
    return (
        <LoginContainer>
            <form action="j_security_check" method="post">
                <h1>Login</h1>
                <div className="container">
                    <div>
                    <input type="text" placeholder="Enter Username" name="j_username" required/>
                    </div>
                    <div>
                    <input type="password" placeholder="Enter Password" name="j_password" required/>
                    </div>
                    <div>
                    <button type="submit">Login</button>
                    </div>
                </div>
            </form>
        </LoginContainer>
    );
};

export default Login;