import _ from "lodash";
import {TEAM_COLORS} from '../../Config';
import {Trophy} from "@styled-icons/ionicons-outline";
import {powerApi} from "../../api";
import React from "react";
import styled from "styled-components";

const WinnerDiv = styled.div`
  position: fixed;
  color: ${props => props.color};
  border: 1px solid ${props => props.color};
  left: calc(50% - 400px);
  top: calc(50% - 250px);
  padding: 30px;
  box-sizing: border-box;
  width: 800px;
  height: 600px;
  background-color: #262626;
  border-radius: 5px;
  display: flex;
  flex-direction: column;
  align-items: center;

  .winner {
    flex-grow: 1;
    display: flex;
    align-items: center;
    justify-content: space-around;

    h1 {
      margin-left: 20px;
      font-size: 2.5rem;
    }
  }
  .scroller {
    overflow-y: auto;
    padding: 0px 20px 0 20px;
    height: 100%;
  }
  h3 {
    color: white;
    margin-bottom: 0px;
    text-align: center;
  }
  li {
    color: white;
    line-height: 25px;
  }
  
  ol {
    padding-bottom: 30px;
  }
  
  .leaderboards {
    display: flex;
  }

  .leaderboards > div {
    height: 400px;
    overflow: hidden;
  }
`;
const compareFn = (a, b) => b.generated - a.generated;
const Leaderboard = (props) => {
  return (
      <div>
          <h3>{props.title}</h3>
          <div className="scroller">
              <ol>
                  {props.players.map((u, id) => (
                      <li key={id}>{u.name} - {powerApi.humanPower(u.generated)}</li>
                  ))}
              </ol>
          </div>
      </div>
  );
};
export const Winner = (props) => {
    const team1 = _.values(props.team1).sort(compareFn);
    const team2 = _.values(props.team2).sort(compareFn);
    const overall = [...team1, ...team2].sort(compareFn);
    return (
        <WinnerDiv color={TEAM_COLORS[props.winner - 1]}>
            <div className="winner">
                <Trophy size={150}/>
                <h1>Team {props.winner} won the game!</h1>
            </div>
            <div className="leaderboards">
                <Leaderboard players={overall} title="Overall leaderboard" />
                <Leaderboard players={team1} title="Team 1" />
                <Leaderboard players={team2} title="Team 2" />
            </div>
        </WinnerDiv>
    );
}