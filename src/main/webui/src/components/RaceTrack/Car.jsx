import * as React from "react"

const Car = (props) => (
    <svg
        width={777}
        height={518}
        viewBox="0 0 777 518"
        xmlSpace="preserve"
        xmlnsXlink="http://www.w3.org/1999/xlink"
        xmlns="http://www.w3.org/2000/svg"
        {...props}
    >
        <image
            width={777}
            height={518}
            preserveAspectRatio="none"
            xlinkHref="race-track-1.png"
        />

        <g className="car" id="car1" >
            <image
                xlinkHref="car-1.png"
            />
        </g>
        <g className="car" id="car2" >
            <image
                xlinkHref="car-2.png"
            />
        </g>
    </svg>
)

export default Car;
